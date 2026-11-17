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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Bg else Txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (on) Txt else Surf2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun FilterLine(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Text(title, color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i -> Choice(options[i], i == selected) { onPick(i) } }
    }
}

/** Recherche de fanfictions : un site, une requête, des filtres précis. */
@Composable
fun SearchTab(hub: Hub) {
    val q = hub.query
    val focus = LocalFocusManager.current
    var filters by remember { mutableStateOf(false) }
    val langs = listOf("" to "Toutes", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "ptBR" to "Portugais")
    val ratings = listOf(0 to "Tous", 10 to "G", 11 to "T", 12 to "M", 13 to "E")
    val words = listOf(0, 10_000, 50_000, 100_000)
    fun go() { focus.clearFocus(); hub.runSearch() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
        item { Title("RECHERCHE", "Trouver une fanfiction") }
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(Surf)
                .border(1.dp, Surf2, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = Mut)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (q.text.isEmpty()) Text("Titre, personnage, thème…", color = Mut, fontSize = 16.sp)
                    BasicTextField(q.text, { hub.query = q.copy(text = it) }, singleLine = true, textStyle = TextStyle(color = Txt, fontSize = 16.sp),
                        cursorBrush = SolidColor(A1), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { go() }), modifier = Modifier.fillMaxWidth())
                }
                if (q.text.isNotEmpty()) Icon(Icons.Default.Close, null, tint = Mut, modifier = Modifier.clickable { hub.query = q.copy(text = "") })
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
            item { FilterLine("TRI", listOf("Pertinence", "Populaires", "Récentes", "Longues"), q.sort) { hub.query = q.copy(sort = it) } }
            item { FilterLine("STATUT", listOf("Toutes", "Terminées", "En cours"), q.status) { hub.query = q.copy(status = it) } }
            item { FilterLine("LONGUEUR", listOf("Toutes", "10k+ mots", "50k+ mots", "100k+ mots"), words.indexOf(q.minWords).coerceAtLeast(0)) { hub.query = q.copy(minWords = words[it]) } }
            if (q.site == "AO3") {
                item { FilterLine("LANGUE", langs.map { it.second }, langs.indexOfFirst { it.first == q.lang }.coerceAtLeast(0)) { hub.query = q.copy(lang = langs[it].first) } }
                item { FilterLine("CLASSEMENT", ratings.map { it.second }, ratings.indexOfFirst { it.first == q.rating }.coerceAtLeast(0)) { hub.query = q.copy(rating = ratings[it].first) } }
                item {
                    Text("FANDOM", color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 8.dp))
                    SearchBox(q.fandom, "ex. One Piece") { hub.query = q.copy(fandom = it) }
                }
            }
            item { Box(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp).fillMaxWidth().height(1.dp).background(Surf2)) }
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
            hub.results.isNotEmpty() && hub.searchMore -> item {
                Box(Modifier.padding(20.dp)) { GhostButton("Charger plus") { hub.runSearch(more = true) } }
            }
            hub.results.isEmpty() -> item {
                Empty("Choisis un site, tape ta recherche, affine avec les filtres. AO3 permet les filtres les plus précis (langue, classement, fandom).")
            }
        }
    }
}
