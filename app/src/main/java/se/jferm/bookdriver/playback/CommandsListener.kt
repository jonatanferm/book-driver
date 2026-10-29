package se.jferm.bookdriver.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup

/**
 * Forwards every callback to [target] unchanged, except that available-command changes
 * report [commands] instead of the inner player's set.
 *
 * Written out by hand on purpose: Kotlin's `by` delegation does NOT forward Java default
 * methods, and every Player.Listener method is one — a delegated wrapper silently drops
 * all events (the UI then shows a stale, paused player).
 */
internal class CommandsListener(
    private val target: Player.Listener,
    private val commands: () -> Player.Commands,
) : Player.Listener {
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = target.onAvailableCommandsChanged(commands())

    override fun onEvents(player: Player, events: Player.Events) = target.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = target.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = target.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = target.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = target.onMediaMetadataChanged(mediaMetadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = target.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)
    @Deprecated("Deprecated in Java") @Suppress("DEPRECATION")
    override fun onLoadingChanged(isLoading: Boolean) = target.onLoadingChanged(isLoading)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = target.onTrackSelectionParametersChanged(parameters)
    @Deprecated("Deprecated in Java") @Suppress("DEPRECATION")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = target.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = target.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = target.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = target.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = target.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = target.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = target.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = target.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = target.onPlayerErrorChanged(error)
    @Deprecated("Deprecated in Java") @Suppress("DEPRECATION")
    override fun onPositionDiscontinuity(reason: Int) = target.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        target.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = target.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = target.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = target.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = target.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = target.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = target.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = target.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = target.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = target.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = target.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = target.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = target.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = target.onRenderedFirstFrame()
    @Deprecated("Deprecated in Java") @Suppress("DEPRECATION")
    override fun onCues(cues: List<Cue>) = target.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = target.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = target.onMetadata(metadata)
}
