package dev.brahmkshatriya.runtimeloader.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class StoreAuthor(
    val name: String,
    val avatar: String?,
    val link: String?,
)

public data class StoreArtifact(
    val path: String,
    val entry: String,
    val resources: List<String>,
)

public data class StoreExtension(
    val id: String,
    val type: String,
    val capabilities: List<String>,
    val name: String,
    val description: String,
    val icon: String?,
    val repoLink: String?,
    val authors: List<StoreAuthor>,
    val updateUrl: String,
    val supportedPlatforms: List<String>,
    val artifacts: Map<String, StoreArtifact>,
) {
    public fun artifactFor(platform: String): StoreArtifact =
        artifacts[platform] ?: error("Extension '$id' has no artifact for '$platform'")
}

public class ExternalExtensionStore private constructor(
    private val files: Map<String, ByteArray>,
    public val extensions: List<StoreExtension>,
) {
    public fun file(path: String): ByteArray =
        files[path] ?: error("Extension store is missing '$path'")

    public fun extensionsFor(platform: String): List<StoreExtension> =
        extensions.filter { platform in it.supportedPlatforms && platform in it.artifacts }

    public companion object {
        public fun fromZip(bytes: ByteArray): ExternalExtensionStore {
            val files = StoredZipReader.read(bytes)
            val catalog = files["catalog.json"] ?: error("Extension store ZIP is missing catalog.json")
            return ExternalExtensionStore(files, parseCatalog(catalog.decodeToString()))
        }
    }
}

private object StoredZipReader {
    private const val LOCAL = 0x04034b50
    private const val CENTRAL = 0x02014b50
    private const val END = 0x06054b50

    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val end = findEnd(bytes)
        val count = u16(bytes, end + 10)
        var offset = u32(bytes, end + 16)
        val result = linkedMapOf<String, ByteArray>()

        repeat(count) {
            check(u32(bytes, offset) == CENTRAL) { "Invalid ZIP central-directory entry" }
            val method = u16(bytes, offset + 10)
            check(method == 0) { "Demo extension store only supports STORED ZIP entries (method=$method)" }
            val compressedSize = u32(bytes, offset + 20)
            val nameLength = u16(bytes, offset + 28)
            val extraLength = u16(bytes, offset + 30)
            val commentLength = u16(bytes, offset + 32)
            val localOffset = u32(bytes, offset + 42)
            val name = bytes.copyOfRange(offset + 46, offset + 46 + nameLength).decodeToString()

            check(u32(bytes, localOffset) == LOCAL) { "Invalid ZIP local entry for '$name'" }
            val localNameLength = u16(bytes, localOffset + 26)
            val localExtraLength = u16(bytes, localOffset + 28)
            val dataStart = localOffset + 30 + localNameLength + localExtraLength
            val dataEnd = dataStart + compressedSize
            check(dataEnd <= bytes.size) { "ZIP entry '$name' is truncated" }
            if (!name.endsWith('/')) result[name] = bytes.copyOfRange(dataStart, dataEnd)

            offset += 46 + nameLength + extraLength + commentLength
        }
        return result
    }

    private fun findEnd(bytes: ByteArray): Int {
        var index = bytes.size - 22
        val minimum = maxOf(0, bytes.size - 65_557)
        while (index >= minimum) {
            if (u32(bytes, index) == END) return index
            index--
        }
        error("ZIP end-of-central-directory was not found")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}

private fun parseCatalog(text: String): List<StoreExtension> {
    val root = Json.parseToJsonElement(text).jsonObject
    return root.requiredArray("extensions").map { element ->
        val obj = element.jsonObject
        StoreExtension(
            id = obj.requiredString("id"),
            type = obj.requiredString("type"),
            capabilities = obj.requiredArray("capabilities").strings(),
            name = obj.requiredString("name"),
            description = obj.requiredString("description"),
            icon = obj.optionalString("icon"),
            repoLink = obj.optionalString("repoLink"),
            authors = obj.requiredArray("authors").map { authorElement ->
                val author = authorElement.jsonObject
                StoreAuthor(
                    name = author.requiredString("name"),
                    avatar = author.optionalString("avatar"),
                    link = author.optionalString("link"),
                )
            },
            updateUrl = obj.requiredString("updateUrl"),
            supportedPlatforms = obj.requiredArray("supportedPlatforms").strings(),
            artifacts = obj.requiredObject("artifacts").mapValues { (_, value) ->
                val artifact = value.jsonObject
                StoreArtifact(
                    path = artifact.requiredString("path"),
                    entry = artifact.requiredString("entry"),
                    resources = artifact.requiredArray("resources").strings(),
                )
            },
        )
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("catalog.json is missing '$name'")

private fun JsonObject.optionalString(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.content.takeUnless { it == "null" }
}

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("catalog.json is missing '$name'")

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name]?.jsonObject ?: error("catalog.json is missing '$name'")

private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

@Composable
public fun ExtensionStorePage(
    extensions: List<StoreExtension>,
    onOpen: (StoreExtension) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("External Extension Store", style = MaterialTheme.typography.headlineMedium)
        Text("Metadata and code below came from a ZIP loaded at runtime.")
        extensions.forEach { extension ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(extension.name, style = MaterialTheme.typography.titleLarge)
                    Text(extension.description)
                    Text("Type: ${extension.type}")
                    Text("Capabilities: ${extension.capabilities.joinToString()}")
                    Text("Authors: ${extension.authors.joinToString { it.name }}")
                    Button(onClick = { onOpen(extension) }) {
                        Text("Open extension")
                    }
                }
            }
        }
    }
}

@Composable
public fun ExtensionContentPage(
    extension: StoreExtension,
    plugin: Plugin,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Column {
                Text(extension.name, style = MaterialTheme.typography.titleLarge)
                Text(extension.id, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            PluginHost(plugin)
        }
    }
}
