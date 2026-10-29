package se.jferm.bookdriver.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

class CommandsListenerTest {
    /** Guards against a new Player.Listener callback being silently dropped by the wrapper. */
    @Test fun overridesEveryListenerMethod() {
        val missing = Player.Listener::class.java.methods
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { m ->
                runCatching { CommandsListener::class.java.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure
            }
            .map { it.name }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun forwardsEvents() {
        var playing: Boolean? = null
        val target = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        CommandsListener(target) { Player.Commands.EMPTY }.onIsPlayingChanged(true)
        assertEquals(true, playing)
    }
}
