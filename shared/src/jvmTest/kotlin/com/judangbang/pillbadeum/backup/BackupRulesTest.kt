package com.judangbang.pillbadeum.backup

import com.judangbang.pillbadeum.backup.BackupRules.DOMAIN_DATABASE
import com.judangbang.pillbadeum.backup.BackupRules.Rule
import com.judangbang.pillbadeum.data.DidITakeDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-028, REQ-055: the backup rule files and the manifest say what record-rules-supplement.md
 * B-1 to B-3 say. What Android does with them is checked on an emulator (docs/07-qa).
 */
class BackupRulesTest {
    private val db = DidITakeDatabase.FILE_NAME

    /** B-1: the record database and its companion files, nothing else. */
    private val backedUpFiles = listOf(db, "$db-wal", "$db-journal")

    @Test
    fun manifest_turnsBackupOnWithBothRuleFiles() {
        val app = BackupRules.childElements(BackupRules.parse(BackupRules.androidAppFile("AndroidManifest.xml")))
            .single { it.tagName == "application" }
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("true", app.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(android, "fullBackupContent"))
        assertEquals("", app.getAttributeNS(android, "backupAgent"), "Auto Backup only, no own agent (R-1)")
    }

    /** Android 12+: cloud backup needs encryption (Q16); device transfer does not (B-3). */
    @Test
    fun dataExtractionRules_android12Plus() {
        val root = BackupRules.parse(BackupRules.androidAppFile("res/xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", root.tagName)
        val sections = BackupRules.childElements(root)
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName })

        val cloud = sections[0]
        assertEquals("true", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
        assertEquals(includes(requireFlags = null), BackupRules.rulesOf(cloud))

        val transfer = sections[1]
        assertEquals("", transfer.getAttribute("disableIfNoEncryptionCapabilities"))
        assertEquals(includes(requireFlags = null), BackupRules.rulesOf(transfer))
    }

    /**
     * Android 11 and earlier: every include needs a flag. An include without one would upload
     * unencrypted records on a phone without a screen lock (Q16), and on Android 8.x the flag is
     * what stops the backup (B-3 limit).
     */
    @Test
    fun fullBackupContent_android11AndEarlier() {
        val root = BackupRules.parse(BackupRules.androidAppFile("res/xml/backup_rules.xml"))
        assertEquals("full-backup-content", root.tagName)
        val rules = BackupRules.rulesOf(root)
        assertTrue(rules.all { it.requireFlags != null }, "every include carries requireFlags: $rules")
        assertEquals(
            includes("clientSideEncryption") + includes("deviceToDeviceTransfer"),
            rules,
        )
    }

    private fun includes(requireFlags: String?): List<Rule> =
        backedUpFiles.map { Rule(tag = "include", domain = DOMAIN_DATABASE, path = it, requireFlags = requireFlags) }
}
