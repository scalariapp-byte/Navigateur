# Navigateur — extension Chrome

Remplace la page Nouvel onglet par :
- **Apps** : vos sites rangés en groupes (ajout `+`, clic droit = retirer, ✎ renommer, ↑ réordonner). Synchronisés entre vos Chrome via votre compte Google.
- **Mots de passe** : coffre chiffré localement (PBKDF2-SHA256 600 000 itérations → AES-256-GCM). Recherche, générateur, export/import du fichier chiffré.

Icône de l'extension : remplir/copier l'identifiant du site courant, enregistrer un identifiant capturé à la connexion (badge `+`), générer un mot de passe.

## Installation
1. `chrome://extensions` → activer **Mode développeur**
2. **Charger l'extension non empaquetée** → dossier `extension/`
3. Ouvrir un nouvel onglet → onglet *Mots de passe* → créer le mot de passe maître (12+ caractères, **irrécupérable si oublié**).

## Sécurité
- Seul le chiffré est écrit sur disque. La clé déverrouillée reste en RAM (`storage.session`), inaccessible aux pages web ; verrouillage auto après 15 min d'inactivité et à la fermeture de Chrome.
- Les pages web ne peuvent que *proposer* un identifiant ; lister/lire le coffre est réservé aux pages de l'extension.
- Le remplissage est manuel (clic) et vérifie que le domaine correspond exactement.
- Désactivez le gestionnaire intégré de Chrome (`chrome://password-manager/settings`) pour éviter les doubles propositions.
