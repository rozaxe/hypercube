package me.rozaxe.hypercube.samples.chat

import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.content.defaultResource
import io.ktor.content.resources
import io.ktor.content.static
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import me.rozaxe.hypercube.Hypercube
import me.rozaxe.hypercube.hypercube
import me.rozaxe.hypercube.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
	embeddedServer(Netty, port = 8080, module = Application::main).start(wait = true)
}

fun Application.main() {
	ChatApplication().apply { main() }
}

class ChatApplication {

	fun Application.main() {
		install(Hypercube)

		routing {

			hypercube {
				val server = ChatServer(this)

				onOpen {
					server.memberJoin(id)
				}

				on("message") { message: String ->
					server.receivedMessage(id, message)
				}

				on("nickname") { nickname: String ->
					server.changeNickname(id, nickname)
				}

				onClose {
					server.memberLeft(id)
				}
			}

			static {
				defaultResource("index.html", "web")
				resources("web")
			}
		}
	}
}

class ChatServer(private val hypercube: HypercubeSockets) {

	/**
	 * Data to serialize when emitting "message" event
	 */
	private data class Message(val from: String, val content: String)

	/**
	 * Data to serialize when emitting "nickname" event
	 */
	private data class Nickname(val old: String, val new: String)

	private val membersName: MutableMap<String, String> = ConcurrentHashMap()

	private var userCount = AtomicInteger()

	suspend fun memberJoin(socketId: String) {
		membersName[socketId] = "user${userCount.incrementAndGet()}"
		hypercube.broadcast("memberJoin", membersName.getValue(socketId))
	}

	suspend fun receivedMessage(senderId: String, content: String) {
		hypercube.broadcast("message", Message(membersName.getValue(senderId), content))
	}

	suspend fun changeNickname(senderId: String, newNickname: String) {
		val oldNickname = membersName.getValue(senderId)
		membersName[senderId] = newNickname
		hypercube.broadcast("nickname", Nickname(oldNickname, newNickname))
	}

	suspend fun memberLeft(socketId: String) {
		hypercube.broadcast("memberLeft", membersName.getValue(socketId))
		membersName.remove(socketId)
	}
}
