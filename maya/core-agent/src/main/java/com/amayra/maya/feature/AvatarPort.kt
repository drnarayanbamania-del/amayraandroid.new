package com.amayra.maya.feature

/**
 * Avatar surface consumed by the agent core without a dependency on the
 * Live2D feature module. Implemented by [com.amayra.maya.avatar.AvatarController]
 * and registered by the composition root.
 */
interface AvatarPort {
    fun onSpeechStart(text: String)
    fun onSpeechEnd()
}
