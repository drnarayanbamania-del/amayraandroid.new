package com.amayra.maya.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the deterministic "open X" matcher: aliases, Hindi verbs, non-commands. */
class OpenAppIntentTest {

    @Test
    fun `english open commands`() {
        assertEquals("WhatsApp", OpenAppIntent.match("open whatsapp")!!.appName)
        assertEquals("Camera", OpenAppIntent.match("Open Camera")!!.appName)
        assertEquals("YouTube", OpenAppIntent.match("open youtube")!!.appName)
    }

    @Test
    fun `hindi and hinglish variants`() {
        assertEquals("WhatsApp", OpenAppIntent.match("whatsapp kholo")!!.appName)
        assertEquals("Camera", OpenAppIntent.match("camera khol do")!!.appName)
        assertEquals("YouTube", OpenAppIntent.match("youtube chalu kar")!!.appName)
        assertEquals("Instagram", OpenAppIntent.match("insta kholna hai")!!.appName)
    }

    @Test
    fun `typos in app names still match via alias substring`() {
        assertEquals("WhatsApp", OpenAppIntent.match("open whatapp")!!.appName)
        assertEquals("WhatsApp", OpenAppIntent.match("open watsapp")!!.appName)
    }

    @Test
    fun `non-commands route to the model`() {
        assertNull(OpenAppIntent.match("kya haal hai"))
        assertNull(OpenAppIntent.match("what is the weather tomorrow in delhi?"))
        assertNull(OpenAppIntent.match("mere dost ko whatsapp pe message likho ki kal aa raha hoon"))
        assertNull(OpenAppIntent.match(""))
        assertNull(OpenAppIntent.match("hello amayra how are you doing today my friend"))
        assertNull(OpenAppIntent.match("close whatsapp"))   // 'close' is not an open verb
    }

    @Test
    fun `filler words are stripped`() {
        assertEquals("WhatsApp", OpenAppIntent.match("please open the whatsapp app")!!.appName)
        assertEquals("Camera", OpenAppIntent.match("zara camera kholo ji")!!.appName)
    }
}
