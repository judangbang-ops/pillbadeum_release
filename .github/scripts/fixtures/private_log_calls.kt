// Synthetic inputs for the privacy checker. This file is never compiled into the app.
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
}
