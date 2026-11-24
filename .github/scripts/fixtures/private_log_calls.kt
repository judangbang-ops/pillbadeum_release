// Synthetic inputs for the privacy checker. This file is never compiled into the app.
import android.util.Log as DeviceLog
import timber.log.Timber as TreeLog

private const val TAG = "PrivacyFixture"

fun privateLogCalls() {
    Log.d(TAG, "saved $medication")
    Log.d(TAG, "dosage ${row.quantity}")
    Log.d(TAG, "condition $diagnosis")
    Log.d(TAG, "procedure " + procedure)
    Log.d(TAG, "allergy ${entry.substance}")
    Log.d(TAG, "implant $implantedDevice")
    Log.d(TAG, "clinic $department")
    Log.d(TAG, "doctor $doctor")
    Log.d(TAG, "guardian $phone")
    Log.d(TAG, "copied $clipboard")
    Log.println(3, TAG, "export $uri")
    Log.d("$guardian", "fixed message")
    Log.d(TAG, "mixed ${allergy + record.id}")
    Log.d(TAG, "record ${record.name}")
    val x = card.medications.first().name
    Log.d(TAG, "value=$x")
    println("dose=" + x)
    println("""raw \$x""")
    DeviceLog.e(TAG, "constant error description", error)
    TreeLog.tag("fixture").e(error, "failed: $x")
    Timber.d("the formatter received %s", x)
    error.printStackTrace()
    Log.e(TAG, "error: $error")
    Log.d(TAG, "device=${fields.first().size}")
    Log.d(TAG, "clinic=${if (ready) x else ""}")
    Log.d(TAG, String.format("contact: %s", x))
}
