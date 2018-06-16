# Hypercube

[![Ktor dependency](https://img.shields.io/badge/ktor-0.9.2-blue.svg?style=flat)](https://github.com/ktorio/ktor)
[![Build Status](https://travis-ci.org/rozaxe/hypercube.svg?branch=master)](https://travis-ci.org/rozaxe/hypercube)
[![GitHub License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat)](https://opensource.org/licenses/MIT)

Hypercube is minimal event based wrapper for Ktor WebSockets.

```kotlin
import io.ktor.application.install
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import me.rozaxe.hypercube.*

fun main(args: Array<String>) {
    embeddedServer(Netty, 8080) {
        install(Hypercube)
        routing {
            hypercube {
                onOpen {
                    broadcast("info", "Member joined")
                }

                on("message", String::class) { content ->
                    broadcast("message", content)
                }

                onClose {
                    broadcast("info", "Member left")
                }
            }
        }
    }.start(wait = true)
}
```

- Runs a minimal WebSockets chat on `ws://localhost:8080`
- Retrieves message from client
- Broadcasts it back to clients


## Installation

This library uses [JitPack](https://jitpack.io).

Add the JitPack repository
```
repositories {
    maven { url "https://jitpack.io" }
}
```

Add the dependency for Hypercube 
```
dependencies {
    compile 'com.github.rozaxe:hypercube:0.1.0'
}
```


## Guide

```kotlin
fun main(args: Array<String>) {
    embeddedServer(Netty, 8080) {

        // Exposes [WebSockets.WebSocketOptions]
        install(Hypercube) {
            pingPeriod = null
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        routing {

            // Optional endpoint, by default listen on "/"
            hypercube("/hc") {

                // Called when a client connects
                onOpen {

                    // Inform everyone someone connects
                    broadcast("info", "Member joined")
                }

                // Register for "message" event, and parse data as string
                on("message", String::class) { content ->
                    // "content" is now a fully casted string

                    // Emit back an event to the client
                    emit("message", content)
                }

                // Register for "hello" event with no data
                on("hello") {

                    // "id" is the unique identifier for this client
                    println(id) 
                }

                // Called when a client left
                onClose {

                    // Send a raw string to everyone
                    broadcastRaw("Member left")
                }

                // An incoming event was not registered
                onUnknownEvent {
                
                    // Send a raw string to the client
                    send("Stop doing that")                
                }

                // An incoming message could not be parsed
                onParsingError {

                    // Close client socket
                     close()
                }
            }
        }
    }
}
```

Also, a complete chat application is available in `samples/chat`.


## Protocol

To exchange with a running Hypercube server, a client's message must respects the following protocol `{"event":"$event","data":$obj}` where `$event` is the event to notify the server and `$obj` a serialized object to pass with the event.


## Versioning

Hypercube depends on [Ktor](https://github.com/ktorio/ktor), which is not stable *yet*, so you may expect break on version update.
