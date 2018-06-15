var socket = null
var messagesDiv = null
var input = null

function connect() {
    console.log("Begin connect")
    socket = new WebSocket("ws://" + window.location.host)

    socket.onerror = function() {
        console.log("Socket error")
    }

    socket.onopen = function() {
        // Do nothing
    }

    socket.onclose = function() {
        // Retry later
        setTimeout(connect, 5000)
    }

    socket.onmessage = function(event) {
        var message = JSON.parse(event.data)
        received(message.event, message.data)
    }
}

function received(event, content) {
    switch (event) {
        case "memberJoin":
            info(content + " joined the chat.")
            break
        case "memberLeft":
            info(content + " left the chat.")
            break
        case "message":
            write(content.from, content.content)
            break
        case "nickname":
            info(content.old + " change their nickname to " + content.new + ".")
            break
    }
}

function info(content) {
    var line = document.createElement("p")
    line.className = "italic"
    line.textContent = content

    messagesDiv.appendChild(line)
    messagesDiv.scrollTop = line.offsetTop
}

function write(from, content) {
    var line = document.createElement("p")
    line.textContent = "[" + from + "] " + content

    messagesDiv.appendChild(line)
    messagesDiv.scrollTop = line.offsetTop
}

function onSend() {
    var text = input.value
    if (text && socket) {
        if (text.startsWith(":")) {
            socket.send('{"event":"nickname","data":"' + text.substring(1) + '"}')
        } else {
            socket.send('{"event":"message","data":"' + text + '"}')
        }
        input.value = ""
    }
}

function main() {
    connect()

    input = document.getElementById("input")
    messagesDiv = document.getElementById("messages")
    document.getElementById("send").onclick = onSend
    input.onkeydown = function(e) {
        if (e.key === "Enter") {
            onSend()
        }
    }
}

main()
