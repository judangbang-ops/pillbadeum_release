package com.judangbang.pillbadeum.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The privacy policy address in the app (REQ-063, TASK-035, TC-036). An installed app cannot change it, so the file it
 * names must stay on main at that path, published: no `{…}` blanks left and an effective date.
 */
class PrivacyPolicyUrlTest {
    private val prefix = "https://github.com/judangbang-ops/pillbadeum_release/blob/main/"

    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, ".git").exists() && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(checkNotNull(dir) { "repository root not found above the working directory" }, path)
    }

    @Test
    fun the_address_names_the_policy_file_on_main() {
        assertTrue(PRIVACY_POLICY_URL.startsWith(prefix), PRIVACY_POLICY_URL)
        val file = repoFile(PRIVACY_POLICY_URL.removePrefix(prefix))
        assertTrue(file.isFile, "$file is missing: the app opens $PRIVACY_POLICY_URL")
    }

    @Test
    fun the_policy_file_is_published() {
        val text = repoFile(PRIVACY_POLICY_URL.removePrefix(prefix)).readText()
        Regex("""\{[^{}\n]+\}""").find(text)?.let { fail("blank left in the policy: ${it.value}") }
        assertTrue(Regex("""(?m)^시행일: \d{4}년 \d{1,2}월 \d{1,2}일$""").containsMatchIn(text), "no effective date line")
        assertTrue("**초안**" !in text, "the draft box is still there")
    }
}
