package me.rozaxe.hypercube

import io.ktor.application.install
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.routing.routing
import io.ktor.server.testing.withTestApplication
import kotlinx.coroutines.experimental.channels.ClosedReceiveChannelException
import org.junit.Test
import kotlin.test.*

class HypercubeTest {

	@Test
	fun shouldEchoHello() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("echo", String::class) { str ->
						send("ECHO $str")
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"echo","data":"hello"}"""))
				assertEquals("ECHO hello", (incoming.receive() as Frame.Text).readText())
			}
		}
	}

	@Test
	fun shouldReceiveWithoutData() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("ping") {
						send("pong")
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"ping"}"""))
				assertEquals("pong", (incoming.receive() as Frame.Text).readText())
			}
		}
	}

	@Test
	fun shouldCallOnOpenBeforeRouting() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					onOpen {
						send("hello")
					}
					on("ping") {
						send("pong")
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"ping"}"""))
				assertEquals("hello", (incoming.receive() as Frame.Text).readText())
				assertEquals("pong", (incoming.receive() as Frame.Text).readText())
			}
		}
	}

	@Test
	fun shouldCallOnCloseAfterRouting() {
		withTestApplication {
			var control = false

			application.install(Hypercube)
			application.routing {
				hypercube {
					on("bye") {
						send("bye")
						close()
					}
					onClose {
						control = true
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"bye"}"""))
				assertEquals("bye", (incoming.receive() as Frame.Text).readText())
				assert(incoming.receive() is Frame.Close)
				Thread.sleep(5)
				assertTrue(control)
			}
		}
	}

	@Test
	fun shouldNotReceiveAfterClosing() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("bye") {
						send("bye")
						close()
					}
				}
			}

			assertFailsWith<ClosedReceiveChannelException> {
				handleWebSocketConversation("/") { incoming, outgoing ->
					outgoing.send(Frame.Text("""{"event":"bye"}"""))
					assertEquals("bye", (incoming.receive() as Frame.Text).readText())
					assert(incoming.receive() is Frame.Close)

					outgoing.send(Frame.Text("""{"event":"bye"}"""))
					assertEquals("bye", (incoming.receive() as Frame.Text).readText())
				}
			}
		}
	}

	@Test
	fun shouldEmitMessage() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("echo", String::class) { str ->
						emit("message", str)
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"echo","data":"hello"}"""))
				assertEquals("""{"event":"message","data":"hello"}""", (incoming.receive() as Frame.Text).readText())
			}
		}
	}

	/**
	 * Used as a complex object to parse
	 */
	data class Person(
			val name: String,
			val age: Int?,
			val devise: String?,
			val favoriteFood: Food,
			val pets: List<Animal>
	) {
		enum class Food {
			ICE_CREAM,
			FRENCH_FRIES,
		}

		data class Animal(val name: String)
	}

	@Test
	fun shouldParseManyDataType() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("int", Int::class) { int ->
						assertEquals(42, int)
					}

					on("string", String::class) { str ->
						assertEquals("lorem", str)
					}

					on("array", Array<Int>::class) { array ->
						assert(arrayOf(1, 2, 3).contentEquals(array))
					}

					on("person", Person::class) { person ->
						assertEquals("alice", person.name)
						assertEquals(42, person.age)
						assertNull(person.devise)
						assertEquals(Person.Food.FRENCH_FRIES, person.favoriteFood)
						assertEquals(listOf(Person.Animal("jack")), person.pets)
					}

					on("close") {
						close()
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"int","data":42}"""))
				outgoing.send(Frame.Text("""{"event":"string","data":"lorem"}"""))
				outgoing.send(Frame.Text("""{"event":"array","data":[1,2,3]}"""))
				outgoing.send(Frame.Text("""{"event":"person","data":{"name":"alice","age":42,"favoriteFood":"FRENCH_FRIES","pets":[{"name":"jack"}]}}"""))

				// Block until all tests pass
				outgoing.send(Frame.Text("""{"event":"close"}"""))
				incoming.receive()
			}
		}
	}

	@Test
	fun shouldNotBreakOnParsingError() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("int", Int::class) { _ ->
						fail()
					}

					on("close") {
						close()
					}
				}
			}

			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"int","data":"hello"}"""))
				outgoing.send(Frame.Text("""{"event":"int"}"""))
				outgoing.send(Frame.Text("""{"event":"i"""))

				// Block until all tests pass
				outgoing.send(Frame.Text("""{"event":"close"}"""))
				incoming.receive()
			}
		}
	}

	@Test
	fun shouldCallbackParsingError() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("int", Int::class) { _ ->
						fail()
					}

					onParsingError {
						close()
					}
				}
			}

			// Wrong data type
			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"int","data":"hello"}"""))

				// A parsing error has occurred, server should have close the socket
				assert(incoming.receive() is Frame.Close)
			}

			// Malformed JSON
			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"int","data":"he"""))

				// A parsing error has occurred, server should have close the socket
				assert(incoming.receive() is Frame.Close)
			}
		}
	}

	@Test
	fun shouldCallbackUnknownEvent() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("hello") {
						fail()
					}

					on("int", Int::class) { _ ->
						fail()
					}

					onUnknownEvent { event ->
						assertEquals("string", event)
						close()
					}
				}
			}

			// Unknown function
			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"string","data":"hello"}"""))

				// A parsing error has occurred, server should have close the socket
				assert(incoming.receive() is Frame.Close)
			}

			// Unknown callback
			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"string"}"""))

				// A parsing error has occurred, server should have close the socket
				assert(incoming.receive() is Frame.Close)
			}
		}
	}

	@Test
	fun shouldBroadcastEvent() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("message", String::class) { content ->
						broadcast("message", content)
					}
				}
			}

			val log1 = mutableListOf<String>()
			val log2 = mutableListOf<String>()
			handleWebSocketConversation("/") { incoming1, outgoing1 ->
				handleWebSocketConversation("/") { incoming2, _ ->
					outgoing1.send(Frame.Text("""{"event":"message","data":"hello"}"""))
					log1 += (incoming1.receive() as Frame.Text).readText()
					log2 += (incoming2.receive() as Frame.Text).readText()
				}
				outgoing1.send(Frame.Text("""{"event":"message","data":"world"}"""))
				log1 += (incoming1.receive() as Frame.Text).readText()
			}
			assertEquals(listOf(
					"""{"event":"message","data":"hello"}""",
					"""{"event":"message","data":"world"}"""
			), log1)
			assertEquals(listOf(
					"""{"event":"message","data":"hello"}"""
			), log2)
		}
	}

	@Test
	fun shouldBroadcastRaw() {
		withTestApplication {
			application.install(Hypercube)
			application.routing {
				hypercube {
					on("message", String::class) { content ->
						broadcastRaw(content)
					}
				}
			}

			val log1 = mutableListOf<String>()
			val log2 = mutableListOf<String>()
			handleWebSocketConversation("/") { incoming1, outgoing1 ->
				handleWebSocketConversation("/") { incoming2, _ ->
					outgoing1.send(Frame.Text("""{"event":"message","data":"hello"}"""))
					log1 += (incoming1.receive() as Frame.Text).readText()
					log2 += (incoming2.receive() as Frame.Text).readText()
				}
				outgoing1.send(Frame.Text("""{"event":"message","data":"world"}"""))
				log1 += (incoming1.receive() as Frame.Text).readText()
			}
			assertEquals(listOf(
					"hello",
					"world"
			), log1)
			assertEquals(listOf(
					"hello"
			), log2)
		}
	}
}
