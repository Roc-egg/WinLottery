package roc.win.lottery

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform