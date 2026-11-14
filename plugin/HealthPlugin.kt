package app.treckit.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Liest Tageswerte aus Health Connect (Schritte, Schlaf, Puls, Gewicht ...).
 * Nur lesen, nur mit Erlaubnis des Nutzers, nur auf diesem Gerät.
 */
@CapacitorPlugin(name = "TreckitHealth")
class HealthPlugin : Plugin() {

    /** Ein Wert, den Track.it lesen kann. */
    private class Spec(
        val type: KClass<out Record>,
        val metric: AggregateMetric<*>?,
        val digits: Int,
        /** Für Einzelmessungen ohne fertige Tagessumme: Zeitpunkt und Wert einer Messung. */
        val pick: ((Record) -> Pair<Instant, Double>)? = null,
        /** avg = Durchschnitt des Tages, last = letzte Messung des Tages, any = 1, wenn es an dem Tag einen Eintrag gibt */
        val mode: String = "",
        val conv: (Any) -> Double
    )

    private val specs: Map<String, Spec> = linkedMapOf(
        "steps" to Spec(StepsRecord::class, StepsRecord.COUNT_TOTAL, 0) { (it as Long).toDouble() },
        "distance" to Spec(DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL, 2) { (it as Length).inKilometers },
        "active_kcal" to Spec(ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, 0) { (it as Energy).inKilocalories },
        "total_kcal" to Spec(TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord.ENERGY_TOTAL, 0) { (it as Energy).inKilocalories },
        "exercise" to Spec(ExerciseSessionRecord::class, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, 0) { (it as Duration).seconds / 60.0 },
        "floors" to Spec(FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, 0) { (it as Double) },
        "water" to Spec(HydrationRecord::class, HydrationRecord.VOLUME_TOTAL, 2) { (it as Volume).inLiters },
        "food_kcal" to Spec(NutritionRecord::class, NutritionRecord.ENERGY_TOTAL, 0) { (it as Energy).inKilocalories },
        "hr_avg" to Spec(HeartRateRecord::class, HeartRateRecord.BPM_AVG, 0) { (it as Long).toDouble() },
        "hr_max" to Spec(HeartRateRecord::class, HeartRateRecord.BPM_MAX, 0) { (it as Long).toDouble() },
        "hr_min" to Spec(HeartRateRecord::class, HeartRateRecord.BPM_MIN, 0) { (it as Long).toDouble() },
        "resting_hr" to Spec(RestingHeartRateRecord::class, RestingHeartRateRecord.BPM_AVG, 0) { (it as Long).toDouble() },
        "weight" to Spec(WeightRecord::class, WeightRecord.WEIGHT_AVG, 1) { (it as Mass).inKilograms },
        "sleep" to Spec(SleepSessionRecord::class, null, 2) { it as Double },
        "sleep_deep" to Spec(SleepSessionRecord::class, null, 2) { it as Double },
        "sleep_rem" to Spec(SleepSessionRecord::class, null, 2) { it as Double },
        "sleep_light" to Spec(SleepSessionRecord::class, null, 2) { it as Double },
        "bp_sys" to Spec(BloodPressureRecord::class, null, 0, { r -> (r as BloodPressureRecord).let { Pair(it.time, it.systolic.inMillimetersOfMercury) } }, "avg") { it as Double },
        "bp_dia" to Spec(BloodPressureRecord::class, null, 0, { r -> (r as BloodPressureRecord).let { Pair(it.time, it.diastolic.inMillimetersOfMercury) } }, "avg") { it as Double },
        "glucose" to Spec(BloodGlucoseRecord::class, null, 0, { r -> (r as BloodGlucoseRecord).let { Pair(it.time, it.level.inMilligramsPerDeciliter) } }, "avg") { it as Double },
        "body_fat" to Spec(BodyFatRecord::class, null, 1, { r -> (r as BodyFatRecord).let { Pair(it.time, it.percentage.value) } }, "last") { it as Double },
        "spo2" to Spec(OxygenSaturationRecord::class, null, 0, { r -> (r as OxygenSaturationRecord).let { Pair(it.time, it.percentage.value) } }, "avg") { it as Double },
        "hrv" to Spec(HeartRateVariabilityRmssdRecord::class, null, 0, { r -> (r as HeartRateVariabilityRmssdRecord).let { Pair(it.time, it.heartRateVariabilityMillis) } }, "avg") { it as Double },
        "period" to Spec(MenstruationFlowRecord::class, null, 0, { r -> (r as MenstruationFlowRecord).let { Pair(it.time, 1.0) } }, "any") { it as Double }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sdkStatus(): Int = try {
        HealthConnectClient.getSdkStatus(context)
    } catch (e: Throwable) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    private fun client(): HealthConnectClient? =
        if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    private fun perm(key: String): String = HealthPermission.getReadPermission(specs.getValue(key).type)

    private fun keysOf(call: PluginCall): List<String> {
        val out = ArrayList<String>()
        val arr = call.getArray("types")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val k = arr.optString(i, "")
                if (specs.containsKey(k) && !out.contains(k)) out.add(k)
            }
        }
        return out
    }

    private fun grantedKeys(granted: Set<String>): JSArray {
        val a = JSArray()
        for (k in specs.keys) if (granted.contains(perm(k))) a.put(k)
        return a
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val s = sdkStatus()
        val r = JSObject()
        r.put("status", when (s) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "unavailable"
        })
        r.put("android", Build.VERSION.SDK_INT)
        r.put("version", 1)
        val types = JSArray()
        for (k in specs.keys) types.put(k)
        r.put("types", types)
        call.resolve(r)
    }

    @PluginMethod
    fun granted(call: PluginCall) {
        val c = client() ?: return call.reject("unavailable")
        scope.launch {
            try {
                val g = c.permissionController.getGrantedPermissions()
                val r = JSObject()
                r.put("types", grantedKeys(g))
                call.resolve(r)
            } catch (e: Throwable) {
                call.reject("failed", e.javaClass.simpleName)
            }
        }
    }

    @PluginMethod
    fun request(call: PluginCall) {
        if (client() == null) return call.reject("unavailable")
        val keys = keysOf(call)
        if (keys.isEmpty()) return call.reject("no_types")
        val perms = HashSet<String>()
        for (k in keys) perms.add(perm(k))
        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            val intent = contract.createIntent(context, perms)
            startActivityForResult(call, intent, "permResult")
        } catch (e: Throwable) {
            call.reject("failed", e.javaClass.simpleName)
        }
    }

    @ActivityCallback
    private fun permResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val c = client() ?: return call.reject("unavailable")
        // Die Antwort des Dialogs ist nicht immer vollständig, deshalb den echten Stand abfragen
        scope.launch {
            try {
                val g = c.permissionController.getGrantedPermissions()
                val r = JSObject()
                r.put("types", grantedKeys(g))
                call.resolve(r)
            } catch (e: Throwable) {
                call.reject("failed", e.javaClass.simpleName)
            }
        }
    }

    @PluginMethod
    fun openSettings(call: PluginCall) {
        val s = sdkStatus()
        val tries = ArrayList<Intent>()
        if (s == HealthConnectClient.SDK_AVAILABLE) {
            if (Build.VERSION.SDK_INT >= 34) tries.add(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
            tries.add(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
        }
        tries.add(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")))
        tries.add(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
        for (i in tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                call.resolve()
                return
            } catch (e: Throwable) {
                // nächste Möglichkeit
            }
        }
        call.reject("failed")
    }

    private fun round(v: Double, digits: Int): Double {
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(v * f) / f
    }

    @PluginMethod
    fun getDaily(call: PluginCall) {
        val c = client() ?: return call.reject("unavailable")
        val keys = keysOf(call)
        if (keys.isEmpty()) return call.reject("no_types")
        val days = (call.getInt("days") ?: 7).coerceIn(1, 400)
        scope.launch {
            try {
                val granted = c.permissionController.getGrantedPermissions()
                val ok = keys.filter { granted.contains(perm(it)) }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val first = today.minusDays((days - 1).toLong())
                val out = LinkedHashMap<String, JSObject>()
                var d = first
                while (!d.isAfter(today)) {
                    out[d.toString()] = JSObject()
                    d = d.plusDays(1)
                }
                // Summen und Durchschnitte pro Tag (Health Connect entfernt doppelte Daten selbst)
                val agg = ok.filter { specs.getValue(it).metric != null }
                if (agg.isNotEmpty()) {
                    val metrics = agg.map { specs.getValue(it).metric!! }.toSet()
                    val groups = c.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = metrics,
                            timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                            timeRangeSlicer = Period.ofDays(1)
                        )
                    )
                    for (g in groups) {
                        val o = out[g.startTime.toLocalDate().toString()] ?: continue
                        for (k in agg) {
                            val sp = specs.getValue(k)
                            @Suppress("UNCHECKED_CAST")
                            val raw = g.result[sp.metric as AggregateMetric<Any>] ?: continue
                            o.put(k, round(sp.conv(raw), sp.digits))
                        }
                    }
                }
                // Schlaf zählt für den Tag, an dem man aufwacht; Wachphasen werden abgezogen
                val sleepKeys = ok.filter { it.startsWith("sleep") }
                if (sleepKeys.isNotEmpty()) {
                    val start = first.minusDays(1).atStartOfDay(zone).toInstant()
                    val end = today.plusDays(1).atStartOfDay(zone).toInstant()
                    val mins = HashMap<String, Double>()
                    val stageMins = HashMap<String, HashMap<String, Double>>()
                    var token: String? = null
                    var pages = 0
                    do {
                        val resp = c.readRecords(
                            ReadRecordsRequest(
                                recordType = SleepSessionRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(start, end),
                                pageSize = 500,
                                pageToken = token
                            )
                        )
                        for (rec in resp.records) {
                            val day = rec.endTime.atZone(zone).toLocalDate().toString()
                            if (!out.containsKey(day)) continue
                            var ms = Duration.between(rec.startTime, rec.endTime).toMillis()
                            for (st in rec.stages) {
                                val sm = Duration.between(st.startTime, st.endTime).toMillis()
                                if (st.stage == SleepSessionRecord.STAGE_TYPE_AWAKE || st.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED || st.stage == 7) {
                                    ms -= sm
                                }
                                val sk = when (st.stage) {
                                    SleepSessionRecord.STAGE_TYPE_DEEP -> "sleep_deep"
                                    SleepSessionRecord.STAGE_TYPE_REM -> "sleep_rem"
                                    SleepSessionRecord.STAGE_TYPE_LIGHT -> "sleep_light"
                                    else -> null
                                }
                                if (sk != null && sm > 0) {
                                    val m2 = stageMins.getOrPut(sk) { HashMap() }
                                    m2[day] = (m2[day] ?: 0.0) + sm / 60000.0
                                }
                            }
                            if (ms > 0) mins[day] = (mins[day] ?: 0.0) + ms / 60000.0
                        }
                        token = resp.pageToken
                        pages++
                    } while (!token.isNullOrEmpty() && pages < 20)
                    if (sleepKeys.contains("sleep")) for ((day, m) in mins) out[day]?.put("sleep", round(m / 60.0, 2))
                    for (sk in sleepKeys) {
                        val m2 = stageMins[sk] ?: continue
                        for ((day, m) in m2) out[day]?.put(sk, round(m / 60.0, 2))
                    }
                }
                // Einzelmessungen (Blutdruck, Blutzucker, Körperfett ...): pro Tag Durchschnitt oder letzter Wert
                for (k in ok) {
                    val sp = specs.getValue(k)
                    val pick = sp.pick ?: continue
                    val sums = HashMap<String, Double>()
                    val cnts = HashMap<String, Int>()
                    val lastAt = HashMap<String, Instant>()
                    val lastVal = HashMap<String, Double>()
                    var token: String? = null
                    var pages = 0
                    do {
                        @Suppress("UNCHECKED_CAST")
                        val resp = c.readRecords(
                            ReadRecordsRequest(
                                recordType = sp.type as KClass<Record>,
                                timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant()),
                                pageSize = 1000,
                                pageToken = token
                            )
                        )
                        for (rec in resp.records) {
                            val pv = pick(rec)
                            val day = pv.first.atZone(zone).toLocalDate().toString()
                            if (!out.containsKey(day)) continue
                            sums[day] = (sums[day] ?: 0.0) + pv.second
                            cnts[day] = (cnts[day] ?: 0) + 1
                            val la = lastAt[day]
                            if (la == null || pv.first.isAfter(la)) { lastAt[day] = pv.first; lastVal[day] = pv.second }
                        }
                        token = resp.pageToken
                        pages++
                    } while (!token.isNullOrEmpty() && pages < 20)
                    for ((day, n) in cnts) {
                        val v = when (sp.mode) {
                            "last" -> lastVal[day] ?: continue
                            "any" -> 1.0
                            else -> (sums[day] ?: 0.0) / n
                        }
                        out[day]?.put(k, round(v, sp.digits))
                    }
                }
                val arr = JSArray()
                for ((day, values) in out) {
                    val o = JSObject()
                    o.put("date", day)
                    o.put("values", values)
                    arr.put(o)
                }
                val missing = JSArray()
                for (k in keys) if (!ok.contains(k)) missing.put(k)
                val r = JSObject()
                r.put("days", arr)
                r.put("missing", missing)
                call.resolve(r)
            } catch (e: SecurityException) {
                call.reject("not_granted")
            } catch (e: Throwable) {
                call.reject("failed", e.javaClass.simpleName)
            }
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
