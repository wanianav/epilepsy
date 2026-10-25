package com.calmguard.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionModelTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun kotlinFeaturesAndForestMatchIndependentPythonPredictions() {
        val json = JSONObject(context.assets.open("parity_vectors.json").bufferedReader().use { it.readText() })
        val model = MotionModel(context)
        val vectors = json.getJSONArray("vectors")
        assertTrue(vectors.length() >= 10)
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val points = vector.getJSONArray("points").let { a -> (0 until a.length()).map { p ->
                a.getJSONArray(p).let { MotionPoint(it.getDouble(0), it.getDouble(1), it.getDouble(2)) }
            } }
            val actual = MotionFeatures.extract(points)
            val expected = vector.getJSONArray("features")
            actual.forEachIndexed { index, value -> assertEquals("feature $index vector $i", expected.getDouble(index), value, 1e-9) }
            assertEquals("forest vector $i", vector.getDouble("expected_score"), model.predict(points), 1e-10)
        }
    }

    @Test fun sampleLibraryHasFiniteCompleteWindowsAndKnownProvenance() {
        val clips = loadClips(context)
        assertEquals(5, clips.size)
        assertEquals(5, clips.map { it.id }.toSet().size)
        clips.forEach { clip ->
            assertTrue(clip.points.size >= 40)
            assertEquals(0, clip.points.size % 40)
            assertTrue(clip.points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
            if (clip.source == "UAL Smart Home") assertEquals("3 (held out)", clip.participant)
        }
        val report = MotionModel(context).report
        assertEquals(3, report.getInt("held_out_user"))
        val users = report.getJSONArray("training_users")
        assertFalse((0 until users.length()).map { users.getInt(it) }.contains(3))
    }

    @Test fun constantWindowIsFiniteAndInvalidDataIsRejected() {
        assertTrue(MotionFeatures.extract(List(40) { MotionPoint(0.0, 0.0, 1.0) }).all { it.isFinite() })
        assertThrows(IllegalArgumentException::class.java) { MotionFeatures.extract(List(40) { MotionPoint(Double.NaN, 0.0, 0.0) }) }
    }
}
