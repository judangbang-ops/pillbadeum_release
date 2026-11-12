package com.judangbang.pillbadeum.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Reads the Android backup rule files of androidApp from the JVM tests (TASK-028).
 *
 * The rule files live in another Gradle module, so the tests find them from the repository root
 * (the directory with settings.gradle.kts) instead of the classpath.
 */
object BackupRules {
    const val DOMAIN_DATABASE: String = "database"

    val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
    }

    fun androidAppFile(relative: String): File = File(repoRoot, "androidApp/src/main/$relative").also {
        check(it.isFile) { "missing $it" }
    }

    /** One `<include>` or `<exclude>` line. [requireFlags] is null when the attribute is absent. */
    data class Rule(val tag: String, val domain: String, val path: String, val requireFlags: String?)

    fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    /** Child rule elements of [parent] in file order. */
    fun rulesOf(parent: Element): List<Rule> {
        val out = mutableListOf<Rule>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            out += Rule(
                tag = node.tagName,
                domain = node.getAttribute("domain"),
                path = node.getAttribute("path"),
                requireFlags = node.getAttribute("requireFlags").takeIf { node.hasAttribute("requireFlags") },
            )
        }
        return out
    }

    fun childElements(parent: Element): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) (children.item(i) as? Element)?.let(out::add)
        return out
    }
}
