package app.fanfichub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Bg else Txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (on) Txt else Surf2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Label(t: String) {
    Text(t.uppercase(), color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 8.dp))
}

@Composable
private fun FilterLine(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Label(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i -> Choice(options[i], i == selected) { onPick(i) } }
    }
}

/** Suggestions AO3 (fandoms, personnages) sous forme de pastilles. */
@Composable
private fun Suggestions(items: List<Pair<String, String>>, onPick: (Pair<String, String>) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { s ->
            Row(Modifier.clip(CircleShape).background(Surf).border(1.dp, Surf2, CircleShape).clickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(s.first + " ", color = A1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(s.second, color = Txt, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Choix dans une longue liste (filtres Spirit), avec recherche. */
@Composable
private fun Picker(f: FormField, onDismiss: () -> Unit, onPick: (Pair<String, String>) -> Unit) {
    var filter by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(24.dp)).background(Surf).padding(vertical = 16.dp)) {
            Text(f.label, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            if (f.options.size > 12) Box(Modifier.padding(top = 12.dp)) { SearchBox(filter, "Filtrer…") { filter = it } }
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(f.options.filter { filter.isBlank() || it.second.contains(filter, true) }) { o ->
                    Text(o.second, color = Txt, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(o) }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/** Recherche de fanfictions : un site, une requête, des filtres précis. */
@Composable
fun SearchTab(hub: Hub) {
    val q = hub.query
    val focus = LocalFocusManager.current
    var filters by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<FormField?>(null) }
    var sugg by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val langs = listOf("" to "Toutes", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "ptBR" to "Portugais")
    val ratings = listOf(0 to "Tous", 10 to "G", 11 to "T", 12 to "M", 13 to "E")
    val words = listOf(0, 10_000, 50_000, 100_000)
    fun go() { focus.clearFocus(); sugg = emptyList(); hub.runSearch() }

    // Suggestions AO3 pendant la frappe
    LaunchedEffect(q.text, q.site) {
        sugg = emptyList()
        if (q.site != "AO3" || q.text.trim().length < 2) return@LaunchedEffect
        delay(350)
        sugg = withContext(Dispatchers.IO) {
            Ao3.autocomplete("character", q.text).take(5).map { "Personnage" to it } + Ao3.autocomplete("fandom", q.text).take(4).map { "Fandom" to it }
        }
    }
    LaunchedEffect(q.site) { if (q.site == "Spirit Fanfics") hub.loadSpiritForm() }
    picking?.let { f -> Picker(f, { picking = null }) { o -> hub.query = q.copy(spirit = q.spirit + (f.name to o)); picking = null } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
        item { Title("RECHERCHE", "Trouver une fanfiction") }
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(Surf)
                .border(1.dp, Surf2, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = Mut)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (q.text.isEmpty()) Text(if (q.site == "Wattpad" && q.wpLists) "Nom de liste, personnage…" else "Titre, personnage, thème…", color = Mut, fontSize = 16.sp)
                    BasicTextField(q.text, { hub.query = q.copy(text = it) }, singleLine = true, textStyle = TextStyle(color = Txt, fontSize = 16.sp),
                        cursorBrush = SolidColor(A1), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { go() }), modifier = Modifier.fillMaxWidth())
                }
                if (q.text.isNotEmpty()) Icon(Icons.Default.Close, null, tint = Mut, modifier = Modifier.clickable { hub.query = q.copy(text = "") })
            }
        }
        item {
            Suggestions(sugg) { (kind, v) ->
                hub.query = if (kind == "Fandom") q.copy(fandom = v, text = "") else q.copy(character = v, text = "")
                sugg = emptyList(); filters = true
            }
        }
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SITE_UI.forEach { s ->
                    val on = q.site == s.name
                    Row(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(14.dp)).background(if (on) s.color.copy(alpha = 0.18f) else Surf)
                        .border(1.dp, if (on) s.color else Surf2, RoundedCornerShape(14.dp)).clickable { hub.query = q.copy(site = s.name) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        SiteLogo(s.name, 22)
                        Text(if (s.name == "FanFiction.net") "FF.net" else s.name.substringBefore(' '), color = if (on) Txt else Mut, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        }
        if (q.site == "Wattpad") item {
            Row(Modifier.padding(start = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Histoires", !q.wpLists) { hub.query = q.copy(wpLists = false) }
                Choice("Listes de lecture", q.wpLists) { hub.query = q.copy(wpLists = true) }
            }
        }
        // Filtres actifs (AO3 : fandom / personnage choisis)
        if (q.site == "AO3" && (q.fandom.isNotBlank() || q.character.isNotBlank())) item {
            Row(Modifier.padding(start = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (q.fandom.isNotBlank()) Choice("Fandom : ${q.fandom}  ✕", true) { hub.query = q.copy(fandom = "") }
                if (q.character.isNotBlank()) Choice("Perso : ${q.character}  ✕", true) { hub.query = q.copy(character = "") }
            }
        }
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (filters) "Masquer les filtres" else "Filtres", color = A1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { filters = !filters }.padding(vertical = 8.dp, horizontal = 2.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Grad).clickable { go() }.padding(horizontal = 22.dp, vertical = 11.dp)) {
                    Text("Rechercher", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (filters) {
            if (q.site == "Spirit Fanfics") {
                if (hub.spiritForm.isEmpty()) item { Empty("Chargement des filtres du site…") }
                items(hub.spiritForm, key = { "f" + it.name }) { f ->
                    val sel = q.spirit[f.name]
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surf)
                        .clickable { picking = f }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label, color = Mut, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(sel?.second ?: "Tous", color = if (sel != null) A1 else Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                        if (sel != null) Text("  ✕", color = Mut, fontSize = 14.sp, modifier = Modifier.clickable { hub.query = q.copy(spirit = q.spirit - f.name) })
                        else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mut)
                    }
                }
            } else {
                item { FilterLine("Tri", listOf("Pertinence", "Populaires", "Récentes", "Longues"), q.sort) { hub.query = q.copy(sort = it) } }
                item { FilterLine("Statut", listOf("Toutes", "Terminées", "En cours"), q.status) { hub.query = q.copy(status = it) } }
                item { FilterLine("Longueur", listOf("Toutes", "10k+ mots", "50k+ mots", "100k+ mots"), words.indexOf(q.minWords).coerceAtLeast(0)) { hub.query = q.copy(minWords = words[it]) } }
            }
            if (q.site == "AO3") {
                item { FilterLine("Langue", langs.map { it.second }, langs.indexOfFirst { it.first == q.lang }.coerceAtLeast(0)) { hub.query = q.copy(lang = langs[it].first) } }
                item { FilterLine("Classement", ratings.map { it.second }, ratings.indexOfFirst { it.first == q.rating }.coerceAtLeast(0)) { hub.query = q.copy(rating = ratings[it].first) } }
            }
            item { Box(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp).fillMaxWidth().height(1.dp).background(Surf2)) }
        }
        // Listes de lecture Wattpad
        val opened = hub.openedList
        if (opened != null) item {
            Row(Modifier.padding(start = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Listes", color = A1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).clickable { hub.openList(null) }.padding(10.dp))
                Text(opened.name, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (hub.listHits.isNotEmpty()) items(hub.listHits, key = { "l" + it.id }) { h ->
            Row(Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surf).clickable { hub.openList(h) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(Surf2)) { if (h.cover.isNotBlank()) NetFill(h.cover) }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(h.name, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${h.count} histoires" + if (h.user.isNotBlank()) " · @${h.user}" else "", color = Mut, fontSize = 13.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mut)
            }
        }
        if (hub.results.isNotEmpty()) item {
            Text("${hub.results.size} résultat" + if (hub.results.size > 1) "s" else "", color = Mut, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp))
        }
        items(hub.results, key = { it.id }) { w ->
            Box {
                WorkCard(w, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { hub.push(Screen.Detail(w.id)) }
                if (!hub.inLibrary(w.id)) Text("+", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 16.dp).clip(CircleShape).background(Grad)
                        .clickable { hub.addToLibrary(w) }.padding(horizontal = 12.dp, vertical = 2.dp))
                else Text("✓", color = A1, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 18.dp))
            }
        }
        when {
            hub.searching -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = A1, strokeWidth = 2.5.dp)
                }
            }
            hub.searchErr != null -> item { Empty(hub.searchErr!!) }
            opened == null && (hub.results.isNotEmpty() || hub.listHits.isNotEmpty()) && hub.searchMore -> item {
                Box(Modifier.padding(20.dp)) { GhostButton("Charger plus") { hub.runSearch(more = true) } }
            }
            hub.results.isEmpty() && hub.listHits.isEmpty() -> item {
                Empty("Choisis un site et tape ta recherche. Sur AO3, des suggestions de personnages et de fandoms s'affichent pendant la frappe.")
            }
        }
    }
}
