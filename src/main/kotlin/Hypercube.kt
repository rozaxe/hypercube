package me.rozaxe.hypercube

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.ktor.application.Application
import io.ktor.application.ApplicationFeature
import io.ktor.application.feature
import io.ktor.application.install
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.readText
import io.ktor.routing.Route
import io.ktor.routing.application
import io.ktor.util.AttributeKey
import io.ktor.util.nextNonce
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.experimental.channels.ClosedReceiveChannelException
import kotlinx.coroutines.experimental.channels.ClosedSendChannelException
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.mapNotNull
import java.io.IOException
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Hypercube support feature. It is required to be installed first before binding any hypercube endpoints.
 *
 * ```
 * install(Hypercube)
 *
 * install(Routing) {
 *     hypercube("/hc") {
 *          onOpen { ... }
 *          on("message", String::class) { ... }
 *          on("ping") { ... }
 *          onClose { ... }
 *     }
 * }
 * ```
 */
class Hypercube {

	/**
	 * Same as [WebSockets.WebSocketOptions]
	 */
	class HypercubeOptions {
		var pingPeriod: Duration? = null
		var timeout: Duration = Duration.ofSeconds(15)
		var maxFrameSize: Long = Long.MAX_VALUE
		var masking: Boolean = false
	}

	companion object Feature : ApplicationFeature<Application, HypercubeOptions, Hypercube> {
		override val key = AttributeKey<Hypercube>("Hypercube")

		override fun install(pipeline: Application, configure: HypercubeOptions.() -> Unit): Hypercube {

			val configuration = HypercubeOptions().apply(configure)
			val feature = Hypercube()

			// Add WebSocket feature to application
			pipeline.install(WebSockets) {
				pingPeriod = configuration.pingPeriod
				timeout = configuration.timeout
				maxFrameSize = configuration.maxFrameSize
				masking = configuration.masking
			}

			return feature
		}
	}
}

/**
 * Data class to use when parsing incoming websocket message with Moshi
 */
internal data class HypercubeProtocol(val event: String, val data: Any?)

/**
 * Bind a type and it's route handling
 */
internal data class HypercubeHandler(val type: KClass<*>, val handler: suspend HypercubeSession.(Any) -> Unit)

/**
 * Store the Hypercube routing.
 */
class HypercubeSockets {

	/**
	 * Store all connected sockets
	 */
	internal val sockets: MutableSet<HypercubeSession> = ConcurrentHashMap.newKeySet()

	/**
	 * Callback on event.
	 */
	internal val callbacks: MutableMap<String, suspend HypercubeSession.() -> Unit> = HashMap()

	/**
	 * Function on event.
	 */
	internal val functions: MutableMap<String, HypercubeHandler> = HashMap()

	/**
	 * Callback on new connection.
	 */
	internal var onOpenCallback: (suspend HypercubeSession.() -> Unit)? = null

	/**
	 * Callback on connection closed.
	 */
	internal var onCloseCallback: (suspend HypercubeId.() -> Unit)? = null

	/**
	 * Callback on error while parsing incoming message.
	 */
	internal var onParsingErrorCallback: (suspend HypercubeSession.() -> Unit)? = null

	/**
	 * Callback when incoming event is unknown
	 */
	internal var onUnknownEventCallback: (suspend HypercubeSession.(event: String) -> Unit)? = null

	/**
	 * Broadcast event and it's data if any to all connected sockets.
	 */
	suspend fun broadcast(event: String, data: Any? = null) {
		for (socket in sockets) {
			socket.emit(event, data)
		}
	}

	/**
	 * Broadcast raw string to all connected sockets.
	 */
	suspend fun broadcastRaw(content: String) {
		for (socket in sockets) {
			socket.send(content)
		}
	}
}

/**
 * Uniquely identify a websocket session
 */
open class HypercubeId(val id: String) : Comparable<HypercubeSession> {
	override fun compareTo(other: HypercubeSession): Int {
		return id.compareTo(other.id)
	}
}

/**
 * Exposes dialogue with a websocket client
 */
class HypercubeSession(id: String, private val socket: WebSocketSession) : HypercubeId(id) {

	/**
	 * Send a raw string to the socket.
	 * @param content  the string to send
	 */
	suspend fun send(content: String) {
		socket.outgoing.send(Frame.Text(content))
	}

	/**
	 * Emit an event and it's data if any to the socket.
	 * @param event  the event to inform the socket
	 * @param data  the data to send with
	 */
	suspend fun emit(event: String, data: Any? = null) {
		val message = HypercubeProtocol(event, data)

		val parser = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
		val protocolAdapter = parser.adapter(HypercubeProtocol::class.java)

		val serialized = protocolAdapter.toJson(message)
		try { socket.outgoing.send(Frame.Text(serialized)) } catch (_: ClosedSendChannelException) {}
	}

	/**
	 * Send close frame to channel.
	 * It will cause [HypercubeSockets.onClose] to be called.
	 */
	suspend fun close() {
		try { socket.outgoing.send(Frame.Close()) } catch (_: ClosedSendChannelException) {}
		try { socket.incoming.cancel() } catch (_: ClosedReceiveChannelException) {}
	}
}

fun Route.hypercube(path: String = "/", handler: HypercubeSockets.() -> Unit) {
	// Check Hypercube feature is installed
	application.feature(Hypercube)

	val hypercubeSockets = HypercubeSockets()
	handler.invoke(hypercubeSockets)

	// Use Moshi as incoming message parser
	val parser = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
	val protocolAdapter = parser.adapter(HypercubeProtocol::class.java)

	webSocket(path) {
		/** Someone connects, generate a unique id */
		val session = HypercubeSession(nextNonce(), this)

		/** Calls [HypercubeSockets.onOpen] */
		hypercubeSockets.sockets.add(session)
		hypercubeSockets.onOpenCallback?.invoke(session)

		try {
			incoming.mapNotNull { it as? Frame.Text }.consumeEach { frame ->
				val raw = frame.readText()

				val message = try {
					val protocolOrNull = protocolAdapter.fromJson(raw)
					if (protocolOrNull == null) {
						hypercubeSockets.onParsingErrorCallback?.invoke(session)
						return@consumeEach

					} else {
						protocolOrNull
					}

				} catch (_: JsonDataException) {
					hypercubeSockets.onParsingErrorCallback?.invoke(session)
					return@consumeEach

				} catch (_: IOException) {
					hypercubeSockets.onParsingErrorCallback?.invoke(session)
					return@consumeEach
				}

				if (message.data != null) {
					// Data field is present, must be a function call
					val function = hypercubeSockets.functions[message.event]

					if (function == null) {
						hypercubeSockets.onUnknownEventCallback?.invoke(session, message.event)
						return@consumeEach
					}

					val adapter = parser.adapter(function.type.java)
					try {
						val typedData = adapter.fromJsonValue(message.data) ?: throw JsonDataException()
						function.handler.invoke(session, typedData)

					} catch (_: JsonDataException) {
						hypercubeSockets.onParsingErrorCallback?.invoke(session)
					}
				} else {
					// Data field is missing, must be a callback
					if (hypercubeSockets.callbacks.containsKey(message.event)) {
						hypercubeSockets.callbacks.getValue(message.event).invoke(session)

					} else {
						hypercubeSockets.onUnknownEventCallback?.invoke(session, message.event)
					}
				}
			}
		} finally {
			/** Calls [HypercubeSockets.onClose] */
			hypercubeSockets.sockets.remove(session)
			hypercubeSockets.onCloseCallback?.invoke(session)
		}
	}
}

fun HypercubeSockets.onOpen(handler: suspend HypercubeSession.() -> Unit) {
	onOpenCallback = handler
}

fun HypercubeSockets.onClose(handler: suspend HypercubeId.() -> Unit) {
	onCloseCallback = handler
}

fun HypercubeSockets.onParsingError(handler: suspend HypercubeSession.() -> Unit) {
	onParsingErrorCallback = handler
}

fun HypercubeSockets.onUnknownEvent(handler: suspend HypercubeSession.(String) -> Unit) {
	onUnknownEventCallback = handler
}

/**
 * Register a callback for given event
 */
fun HypercubeSockets.on(event: String, handler: suspend HypercubeSession.() -> Unit) {
	callbacks[event] = handler
}

/**
 * Register a function for given event
 */
fun <T : Any> HypercubeSockets.on(event: String, type: KClass<T>, handler:  suspend HypercubeSession.(T) -> Unit) {
	@Suppress("UNCHECKED_CAST") // T derives from Any, so the cast cannot fails (right ?)
	val typedRoute = HypercubeHandler(type, handler as suspend HypercubeSession.(Any) -> Unit)
	functions[event] = typedRoute
}
