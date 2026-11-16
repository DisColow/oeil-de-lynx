# Publier Œil de Lynx sur Google Play — guide pas à pas

Tout ce qui peut être préparé à l’avance est dans ce dossier. Les étapes marquées **(vous)** demandent votre compte Google, un paiement ou une décision : elles ne peuvent pas être faites à votre place.

| Fichier | Contenu |
| --- | --- |
| `fiche-store.md` | Nom, descriptions courte et complète, catégorie, URL de la politique de confidentialité |
| `formulaires.md` | Réponses à tous les formulaires « Contenu de l’application » (sécurité des données, accès, classification, services de premier plan…) |
| `graphics/` | Icône 512 × 512, image de présentation 1 024 × 500, captures d’écran 1 080 × 1 920 |
| `../docs/politique-de-confidentialite.md` | Politique de confidentialité publique |
| Releases GitHub | `oeil-de-lynx-X.Y.Z.aab` : le fichier à envoyer à Google Play (à partir de la 1.16.0) |

## 1. Avant de commencer (vous)

1. Compléter l’**adresse e-mail de contact** dans `docs/politique-de-confidentialite.md` (elle sera publique).
2. Créer un **compte développeur** sur <https://play.google.com/console> : frais uniques de 25 $, vérification d’identité (pièce d’identité, parfois plusieurs jours).
3. ⚠️ **Compte personnel créé récemment** : Google exige un **test fermé avec au moins 12 testeurs pendant 14 jours consécutifs** avant d’autoriser la publication en production. Prévoyez 12 adresses Gmail (famille, amis) qui installent l’application via le lien de test et la gardent installée 14 jours.

## 2. Créer l’application dans la console (vous)

- Nom : voir `fiche-store.md` ; langue par défaut **Français (France)** ; type **Application** ; **Gratuite**.
- Accepter les déclarations (règlement, lois d’exportation).

## 3. Clé de signature — choix important (vous)

À la première publication, la console propose **Play App Signing** :

- **Recommandé : « Utiliser ma propre clé de signature d’application »** et importer la clé actuelle (`oeil-de-lynx-release`, stockée dans `.signing/` sur votre poste) avec l’outil PEPK proposé par la console. Ainsi, la version Play et les APK des releases GitHub ont **la même signature** : les téléphones qui ont déjà installé l’APK pourront recevoir les mises à jour du Play Store sans désinstaller (et sans perdre les clips et appairages).
- Sinon (clé générée par Google), l’app du Play Store sera vue comme différente : il faudra désinstaller la version APK avant d’installer celle du store, et refaire les appairages.

La clé de release actuelle sert dans tous les cas de **clé d’importation** : l’AAB des releases GitHub est déjà signé avec.

## 4. Remplir « Contenu de l’application »

Recopier les réponses de `formulaires.md` (politique de confidentialité, annonces, accès, classification, public cible, sécurité des données, services de premier plan). Pour les services de premier plan, préparer la **courte vidéo de démonstration** décrite dans ce fichier.

## 5. Fiche du store principale

- Textes : `fiche-store.md`.
- Icône, image de présentation, captures : `graphics/`. Ajouter idéalement deux vraies captures prises sur les téléphones (écran caméra filmant, direct sur le spectateur).

## 6. Test fermé (obligatoire pour un compte personnel récent)

1. Tests › **Test fermé** › créer un canal, ajouter la liste des 12 testeurs (adresses e-mail).
2. Créer une version : envoyer `oeil-de-lynx-X.Y.Z.aab` (release GitHub la plus récente).
3. Envoyer le lien d’inscription aux testeurs ; attendre **14 jours** avec au moins 12 testeurs inscrits.
4. Demander ensuite l’**accès à la production** depuis le tableau de bord (questionnaire sur le test).

## 7. Production

Production › créer une version › envoyer le même AAB (ou un plus récent) › pays : France (et autres) › **Envoyer pour examen**. L’examen de Google prend de quelques heures à plusieurs jours ; pour une application caméra/micro avec service de premier plan, des questions complémentaires sont possibles.

## 8. Mises à jour suivantes

Chaque release GitHub (`workflow_dispatch` ou tag `vX.Y.Z`) produit un nouvel `.aab` : l’envoyer dans une nouvelle version de production. Le `versionCode` augmente automatiquement avec le numéro de version.

## Points de vigilance

- **API cible** : Google exige Android 16 (API 36) pour les nouvelles applications depuis le 31 août 2026 ; l’application cible l’API 36 à partir de la 1.16.0.
- **Règlement « stalkerware »** : présenter l’application comme caméra de sécurité pour son propre logement (voir `formulaires.md`, section 11).
- **Réseau** : le direct depuis Internet dépend de la box (UPnP, IPv6) ; ce n’est pas un motif de refus, mais à indiquer aux testeurs de Google (texte prévu dans `formulaires.md`).
