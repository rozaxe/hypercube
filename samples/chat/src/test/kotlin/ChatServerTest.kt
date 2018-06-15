package me.rozaxe.hypercube.samples.chat

import io.ktor.application.Application
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.server.testing.withTestApplication
import org.junit.Test
import kotlin.test.assertEquals

class ChatServerTest {

	@Test
	fun testSimpleConversation() {
		withTestApplication(Application::main) {
			// Using List because messages arrive in a predictable order
			val log = mutableListOf<String>()
			handleWebSocketConversation("/") { incoming, outgoing ->
				outgoing.send(Frame.Text("""{"event":"message","data":"Hello world !"}"""))
				for (n in 0 until 2) {
					log += (incoming.receive() as Frame.Text).readText()
				}
			}
			assertEquals(
					listOf(
							"""{"event":"memberJoin","data":"user1"}""",
							"""{"event":"message","data":{"from":"user1","content":"Hello world !"}}"""
					), log
			)
		}
	}

	@Test
	fun testDualConversation() {
		withTestApplication(Application::main) {
			// Using Set because messages can arrive in various order
			val log1 = mutableSetOf<String>()
			val log2 = mutableSetOf<String>()
			handleWebSocketConversation("/") { incoming1, outgoing1 ->
				log1 += (incoming1.receive() as Frame.Text).readText()
				handleWebSocketConversation("/") { incoming2, outgoing2 ->
					outgoing1.send(Frame.Text("""{"event":"message","data":"Hello"}"""))
					outgoing2.send(Frame.Text("""{"event":"message","data":"Hi"}"""))
					for (n in 0 until 3) log1 += (incoming1.receive() as Frame.Text).readText()
					for (n in 0 until 3) log2 += (incoming2.receive() as Frame.Text).readText()
				}
				log1 += (incoming1.receive() as Frame.Text).readText()
			}
			assertEquals(
					setOf(
							"""{"event":"memberJoin","data":"user1"}""",
							"""{"event":"memberJoin","data":"user2"}""",
							"""{"event":"message","data":{"from":"user1","content":"Hello"}}""",
							"""{"event":"message","data":{"from":"user2","content":"Hi"}}""",
							"""{"event":"memberLeft","data":"user2"}"""
					), log1
			)
			assertEquals(
					setOf(
							"""{"event":"memberJoin","data":"user2"}""",
							"""{"event":"message","data":{"from":"user1","content":"Hello"}}""",
							"""{"event":"message","data":{"from":"user2","content":"Hi"}}"""
					), log2
			)
		}
	}

	@Test
	fun testChangeNickname() {
		withTestApplication(Application::main) {
			val log = mutableListOf<String>()
			handleWebSocketConversation("/") { incoming, outgoing ->
				log += (incoming.receive() as Frame.Text).readText()
				outgoing.send(Frame.Text("""{"event":"nickname","data":"milo"}"""))
				outgoing.send(Frame.Text("""{"event":"message","data":"Hello"}"""))
				log += (incoming.receive() as Frame.Text).readText()
				log += (incoming.receive() as Frame.Text).readText()
			}
			assertEquals(
					listOf(
							"""{"event":"memberJoin","data":"user1"}""",
							"""{"event":"nickname","data":{"old":"user1","new":"milo"}}""",
							"""{"event":"message","data":{"from":"milo","content":"Hello"}}"""
					), log
			)
		}
	}
}
