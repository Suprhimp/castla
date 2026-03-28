package com.castla.mirror.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoryClassifierTest {

    @Test
    fun `test classify navigation apps`() {
        // Known package names
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("com.skt.tmap", "T Map"))
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("com.google.android.apps.maps", "Google Maps"))
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("com.waze", "Waze"))
        
        // Match by package keyword
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("com.mycompany.naviapp", "Unknown App"))
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("org.test.mapviewer", "Viewer"))
        
        // Match by label keyword
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("com.unknown.app", "카카오내비"))
        assertEquals("NAVIGATION", AppCategoryClassifier.classify("com.unknown.app", "구글 지도"))
    }

    @Test
    fun `test classify video apps`() {
        // Known package names
        assertEquals("VIDEO", AppCategoryClassifier.classify("com.google.android.youtube", "YouTube"))
        assertEquals("VIDEO", AppCategoryClassifier.classify("com.netflix.mediaclient", "Netflix"))
        assertEquals("VIDEO", AppCategoryClassifier.classify("com.disney.disneyplus", "Disney+"))
        assertEquals("VIDEO", AppCategoryClassifier.classify("net.cj.cjhv.gs.tving", "Tving"))
        
        // Match by package keyword
        assertEquals("VIDEO", AppCategoryClassifier.classify("com.myapp.video", "Player"))
        assertEquals("VIDEO", AppCategoryClassifier.classify("org.test.ottplayer", "Streamer"))
        
        // Match by label keyword
        assertEquals("VIDEO", AppCategoryClassifier.classify("com.unknown.app", "네이버 동영상"))
        assertEquals("VIDEO", AppCategoryClassifier.classify("com.unknown.app", "무료 영화"))
    }

    @Test
    fun `test classify music apps`() {
        // Known package names
        assertEquals("MUSIC", AppCategoryClassifier.classify("com.spotify.music", "Spotify"))
        assertEquals("MUSIC", AppCategoryClassifier.classify("com.iloen.melon", "Melon"))
        
        // Match by package keyword
        assertEquals("MUSIC", AppCategoryClassifier.classify("com.super.audio.player", "AudioPlayer"))
        assertEquals("MUSIC", AppCategoryClassifier.classify("fm.test.radio", "MyRadio"))
        
        // Match by label keyword
        assertEquals("MUSIC", AppCategoryClassifier.classify("com.unknown.app", "삼성 음악"))
        assertEquals("MUSIC", AppCategoryClassifier.classify("com.unknown.app", "KBS 라디오"))
    }

    @Test
    fun `test classify other apps`() {
        assertEquals("OTHER", AppCategoryClassifier.classify("com.android.settings", "Settings"))
        assertEquals("OTHER", AppCategoryClassifier.classify("com.kakao.talk", "KakaoTalk"))
        assertEquals("OTHER", AppCategoryClassifier.classify("com.android.chrome", "Chrome"))
        assertEquals("OTHER", AppCategoryClassifier.classify("com.google.android.gm", "Gmail"))
    }
}
