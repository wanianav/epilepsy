package com.calmguard.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.*

data class MotionPoint(val x: Double, val y: Double, val z: Double)
data class SampleClip(
    val id: String, val title: String, val description: String,
    val source: String, val participant: String, val label: String,
    val hz: Int, val points: List<MotionPoint>
) {
    val windows get() = points.chunked(40).filter { it.size == 40 }
}

/** Same feature order and float32 tree input as tools/train_model.py. */
object MotionFeatures {
    val names = listOf("magnitude_mean", "magnitude_std", "magnitude_range", "magnitude_rms",
        "difference_mean", "difference_std", "difference_max", "axis_std_mean",
        "axis_range_mean", "axis_rms_mean", "magnitude_center_crossings", "lag1_correlation")

    fun extract(points: List<MotionPoint>): DoubleArray {
        require(points.size >= 2) { "A movement window needs at least two readings" }
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        val axes = listOf(points.map { it.x }, points.map { it.y }, points.map { it.z })
        val magnitude = points.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
        fun std(v: List<Double>) = sqrt(v.sumOf { (it - v.average()).pow(2) } / v.size)
        fun range(v: List<Double>) = v.max() - v.min()
        fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)
        val difference = magnitude.zipWithNext { a, b -> abs(b - a) }
        val mean = magnitude.average()
        val centered = magnitude.map { it - mean }
        val denominator = centered.sumOf { it * it }
        val correlation = if (denominator < 1e-12) 0.0 else
            centered.zipWithNext { a, b -> a * b }.sum() / denominator
        val crossings = centered.zipWithNext { a, b -> if ((a >= 0) != (b >= 0)) 1.0 else 0.0 }.sum() / (points.size - 1)
        return doubleArrayOf(mean, std(magnitude), range(magnitude), rms(magnitude),
            difference.average(), std(difference), difference.max(), axes.map(::std).average(),
            axes.map(::range).average(), axes.map(::rms).average(), crossings, correlation)
    }
}

class MotionModel(context: Context) {
    private val json = JSONObject(context.assets.open("motion_forest.json").bufferedReader().use { it.readText() })
    private val trees = json.getJSONArray("trees")
    val report = json.getJSONObject("report")
    val threshold = json.getDouble("threshold")
    init {
        require(json.getJSONArray("features").let { array ->
            (0 until array.length()).map { array.getString(it) } == MotionFeatures.names
        }) { "Model feature contract does not match this application" }
    }
    fun predict(points: List<MotionPoint>) = predictFeatures(MotionFeatures.extract(points))
    fun predictFeatures(features: DoubleArray): Double {
        require(features.size == MotionFeatures.names.size && features.all { it.isFinite() })
        val values = features.map { it.toFloat().toDouble() }
        return (0 until trees.length()).sumOf { index ->
            val tree = trees.getJSONObject(index)
            val split = tree.getJSONArray("feature")
            val thresholds = tree.getJSONArray("threshold")
            val left = tree.getJSONArray("left")
            val right = tree.getJSONArray("right")
            var node = 0
            while (split.getInt(node) >= 0) {
                node = if (values[split.getInt(node)] <= thresholds.getDouble(node)) left.getInt(node) else right.getInt(node)
            }
            tree.getJSONArray("positive_fraction").getDouble(node)
        } / trees.length()
    }
}

fun loadClips(context: Context): List<SampleClip> {
    val array = JSONObject(context.assets.open("sample_clips.json").bufferedReader().use { it.readText() }).getJSONArray("clips")
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val points = item.getJSONArray("points")
        SampleClip(item.getString("id"), item.getString("title"), item.getString("description"),
            item.getString("source"), item.getString("participant"), item.getString("label"), item.getInt("hz"),
            (0 until points.length()).map { p -> points.getJSONArray(p).let { MotionPoint(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } })
    }
}
