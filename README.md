# Hypercube

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


## How to

A complete chat application is available in `samples/chat`.


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
    compile 'com.github.rozaxe:hypercube:master-SNAPSHOT'
}
```


## Protocol

To exchange with a running Hypercube server, a client's message must respect the following protocol `{"event":"$event","data":$obj}` where `$event` is the event to notify the server and `$obj` a serialized object to pass with the event.
