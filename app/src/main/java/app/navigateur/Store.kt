package app.navigateur

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Profile(val id: String, var name: String, var email: String, var color: Int)
data class Group(val id: String, var name: String, var color: Int)
data class Tab(val id: String, var url: String, var title: String, var group: String?, val priv: Boolean = false)
data class Entry(var title: String, var url: String, val t: Long = System.currentTimeMillis())
data class Cred(val host: String, val user: String, val pass: String)

val COLORS = longArrayOf(0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF9333EA, 0xFFEA580C, 0xFF0891B2).map { it.toInt() }
fun uid() = UUID.randomUUID().toString().take(8)
fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
fun <T> List<T>.ja(f: (T) -> Any?) = JSONArray().also { a -> forEach { a.put(f(it)) } }
fun jo(vararg p: Pair<String, Any?>) = JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }

/** Mots de passe chiffrés AES-256-GCM avec une clé non extractible du Keystore Android. */
object Crypto {
    private const val A = "navigateur_pw"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(A, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(A, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun enc(s: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key()); iv + doFinal(s.toByteArray()) }
    fun dec(b: ByteArray): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)); String(doFinal(b, 12, b.size - 12))
    }
}

/** Données d'un profil : onglets, groupes, favoris, historique, exclusions, mots de passe. */
class ProfileData(val p: Profile, private val dir: File) {
    val tabs = mutableListOf<Tab>()
    val groups = mutableListOf<Group>()
    val bookmarks = mutableListOf<Entry>()
    val history = mutableListOf<Entry>()
    val exclusions = mutableListOf<String>()
    val creds = mutableListOf<Cred>()
    var current: String? = null
    private val f get() = File(dir, "p_${p.id}.json")
    private val pf get() = File(dir, "pw_${p.id}.bin")
    private fun entries(a: JSONArray) = a.objs().map { Entry(it.getString("t"), it.getString("u"), it.getLong("d")) }
    private fun ej(e: Entry) = jo("t" to e.title, "u" to e.url, "d" to e.t)

    init {
        if (f.exists()) runCatching {
            val o = JSONObject(f.readText())
            o.getJSONArray("tabs").objs().mapTo(tabs) { Tab(it.getString("id"), it.getString("u"), it.getString("t"), it.str("g")) }
            o.getJSONArray("groups").objs().mapTo(groups) { Group(it.getString("id"), it.getString("n"), it.getInt("c")) }
            bookmarks += entries(o.getJSONArray("bm"))
            history += entries(o.getJSONArray("hist"))
            o.getJSONArray("ex").let { a -> (0 until a.length()).mapTo(exclusions) { a.getString(it) } }
            current = o.str("cur")
        }
        if (pf.exists()) runCatching {
            JSONArray(Crypto.dec(pf.readBytes())).objs().mapTo(creds) { Cred(it.getString("h"), it.getString("u"), it.getString("p")) }
        }
    }

    fun save() = f.writeText(jo(
        "tabs" to tabs.filter { !it.priv }.ja { jo("id" to it.id, "u" to it.url, "t" to it.title, "g" to it.group) },
        "groups" to groups.ja { jo("id" to it.id, "n" to it.name, "c" to it.color) },
        "bm" to bookmarks.ja { ej(it) }, "hist" to history.ja { ej(it) }, "ex" to exclusions.ja { it },
        "cur" to current).toString())

    fun saveCreds() = pf.writeBytes(Crypto.enc(creds.ja { jo("h" to it.host, "u" to it.user, "p" to it.pass) }.toString()))
}

class Store(private val dir: File) {
    val profiles = mutableListOf<Profile>()
    var cur: ProfileData
    private val f get() = File(dir, "profiles.json")

    init {
        var curId: String? = null
        if (f.exists()) runCatching {
            val o = JSONObject(f.readText())
            o.getJSONArray("p").objs().mapTo(profiles) { Profile(it.getString("id"), it.getString("n"), it.getString("e"), it.getInt("c")) }
            curId = o.str("cur")
        }
        if (profiles.isEmpty()) profiles += Profile("perso", "Personnel", "", COLORS[0])
        cur = ProfileData(profiles.firstOrNull { it.id == curId } ?: profiles[0], dir)
    }

    fun save() {
        f.writeText(jo("cur" to cur.p.id, "p" to profiles.ja { jo("id" to it.id, "n" to it.name, "e" to it.email, "c" to it.color) }).toString())
        cur.save()
    }
    fun switch(p: Profile) { cur.save(); cur = ProfileData(p, dir); save() }
    fun delete(p: Profile) { profiles.remove(p); File(dir, "p_${p.id}.json").delete(); File(dir, "pw_${p.id}.bin").delete(); save() }
}
