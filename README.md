# Navigateur (Android)

Navigateur Android basé sur le moteur de Chrome (Android System WebView).

- **Profils** : chacun a son nom, son e-mail, sa couleur et ses cookies/sessions isolés (deux comptes Gmail connectés en parallèle). Chaque profil a ses propres onglets, groupes, favoris, historique et mots de passe.
- **Groupes d'onglets** : créer, renommer, recolorer, déplacer un onglet (⇄), fermer ou dissocier un groupe. La bordure du compteur d'onglets prend la couleur du groupe actif.
- **Onglets privés** (🕶) : rien dans l'historique, cookies effacés à la fermeture du dernier onglet privé.
- **Historique contrôlé** : « Ne jamais enregistrer ce site » + règles par site ou mot-clé (les recherches contenant ce mot sont aussi exclues, et l'historique existant est purgé).
- **Favoris**, affichés sur la page Nouvel onglet.
- **Mots de passe** chiffrés (AES-256-GCM, clé du Keystore Android), protégés par le verrouillage de l'appareil, proposés à l'enregistrement quand vous vous connectez, remplissage via ⋮ → Remplir.

## Obtenir l'APK
Onglet **Actions** du dépôt GitHub → dernier run « APK » → artefact **Navigateur-apk** → dézipper → installer `app-debug.apk` sur le téléphone (autoriser les sources inconnues).

## Limites
- Android uniquement (iOS impose WebKit).
- Ne touche pas à l'application Chrome : son historique et ses mots de passe restent séparés.
- Profils isolés seulement si Android System WebView ≥ 110 environ (l'app prévient sinon).
- Google peut refuser la connexion à un compte dans une WebView malgré le contournement du user-agent.
