# Créer une extension Fanfic Hub

Une extension est un fichier JSON publié sur GitHub. Elle permet de **chercher** sur un site et d'**ouvrir les histoires**
dans le lecteur de l'app (mise en page sombre, traduction, liste des chapitres).

## 1. Le fichier de l'extension (`monsite.json`)

```json
{
  "id": "monsite",              // identifiant unique, minuscules
  "name": "Mon Site",           // nom affiché
  "logo": "MS",                 // 1 à 3 lettres
  "color": "#8E7CFF",           // couleur
  "version": 1,
  "search": "https://monsite.com/recherche?q={q}&page={page}",
  "item": ".resultat",          // un bloc par histoire dans la page de résultats
  "title": "h2 a",              // titre (texte)
  "link": "h2 a@href",          // lien de l'histoire (attribut)
  "author": ".auteur",          // facultatif
  "cover": "img@src",           // facultatif
  "summary": ".resume"          // facultatif
}
```

- Les valeurs sont des **sélecteurs CSS** appliqués à chaque bloc `item`.
- `sélecteur@attribut` lit un attribut (`a@href`, `img@src`, `img@data-src`) ; sinon c'est le texte.
- `{q}` = recherche, `{page}` = numéro de page (commence à 1).
- Retire les commentaires `//` : le JSON ne les accepte pas.

## 2. L'index du dépôt (`index.json`)

```json
{ "extensions": [ { "name": "Mon Site", "url": "https://raw.githubusercontent.com/MOI/MON-DEPOT/main/monsite.json" } ] }
```

## 3. L'ajouter dans l'app

Réglages → Extensions → colle le lien **raw** de ton `index.json` → « Ajouter le dépôt », puis touche l'extension pour l'installer.
Elle apparaît dans l'onglet Recherche.
