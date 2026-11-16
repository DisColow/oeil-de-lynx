# Réponses aux formulaires de la Play Console

Section **Contenu de l’application** (menu « Règles et programmes › Contenu de l’application »). Les réponses ci-dessous correspondent au fonctionnement réel de la version 1.15 et plus.

## 1. Politique de confidentialité

URL : `https://github.com/DisColow/oeil-de-lynx/blob/main/docs/politique-de-confidentialite.md`
(complétez d’abord l’adresse e-mail de contact dans ce fichier).

## 2. Sécurité des données (Data safety)

| Question | Réponse |
| --- | --- |
| Votre application collecte-t-elle ou partage-t-elle l’un des types de données utilisateur requis ? | **Non** |

Justification (pour vous, ce texte n’est pas demandé) : selon les règles de Google, une donnée envoyée hors du téléphone n’a pas à être déclarée si elle est **chiffrée de bout en bout** et lisible uniquement par l’émetteur et le destinataire, sans intermédiaire détenant les clés. C’est le cas ici : images, son, voix, clips et commandes circulent directement entre vos téléphones appairés, chiffrés en AES-256-GCM avec une clé échangée par QR code, que ni l’éditeur ni aucun serveur ne possède. Le reste (détection de mouvement, scan du QR code) est traité uniquement sur le téléphone. Aucun SDK tiers n’envoie de données.

Si la console demande les pratiques de sécurité : « Les données sont chiffrées pendant le transfert » → **Oui**.

## 3. Annonces

Votre application contient-elle des annonces ? → **Non**

## 4. Accès à l’application (pour les testeurs de Google)

Choisir : **Tout ou partie des fonctionnalités sont limitées** et ajouter ces instructions :

```
Aucun compte n’est nécessaire. Toutes les fonctions sont accessibles sans identifiant.
Pour tester le direct, il faut deux appareils Android avec l’application :
1. Appareil A (caméra) : autoriser caméra, micro et notifications, puis Réglages (icône en haut à droite) › Appairer un téléphone spectateur : un QR code s’affiche. Appuyer ensuite sur le grand bouton « Démarrer ».
2. Appareil B (spectateur) : Réglages › Regarder une caméra › Scanner le QR code d’une caméra, puis toucher la caméra pour voir le direct.
Les deux appareils doivent être sur le même Wi-Fi (ou la box doit autoriser l’UPnP pour un accès depuis Internet).
Avec un seul appareil : démarrer la surveillance, bouger devant la caméra, puis ouvrir la Galerie pour voir le clip enregistré.
```

## 5. Classification du contenu (questionnaire IARC)

- Catégorie : **Utilitaire, productivité, communication ou autre**
- Violence, sexualité, langage grossier, substances, jeux d’argent : **Non** à tout
- Les utilisateurs peuvent-ils interagir ou échanger du contenu ? → **Oui** (image, son et voix entre les téléphones appairés de l’utilisateur ; pas d’échange avec des inconnus)
- L’application partage-t-elle la position de l’utilisateur avec d’autres ? → **Non**
- Achats numériques ? → **Non**

## 6. Public cible

- Tranche d’âge : **18 ans et plus** uniquement
- L’application peut-elle attirer involontairement les enfants ? → **Non**

## 7. Applications d’actualités / santé / finances / gouvernement

Non, non concerné.

## 8. Identifiant publicitaire

L’application utilise-t-elle l’identifiant publicitaire ? → **Non**

## 9. Services de premier plan (Android 14 et plus)

La console demande de justifier chaque type de service de premier plan déclaré dans le manifeste, avec une **vidéo de démonstration** (lien YouTube non répertorié ou Google Drive) montrant la fonction.

| Type | Service | Justification à copier |
| --- | --- | --- |
| `camera` | Surveillance (téléphone caméra) | « L’utilisateur démarre la surveillance : la caméra doit continuer à fonctionner écran éteint pour détecter les mouvements et enregistrer des clips. Une notification permanente est affichée tant que la caméra est utilisée ; l’utilisateur l’arrête avec le bouton Arrêter. » |
| `microphone` | Surveillance (téléphone caméra) | « Pendant la surveillance démarrée par l’utilisateur, le micro enregistre la piste audio des clips, détecte les bruits (option) et transmet le son au téléphone spectateur appairé. Notification permanente affichée. » |
| `specialUse` | Alertes (téléphone spectateur) | « Le téléphone spectateur maintient une connexion directe et chiffrée avec le téléphone caméra de l’utilisateur, sans serveur, pour afficher immédiatement une alerte de mouvement. Il n’existe pas de serveur de notifications push : ce service est la seule façon de prévenir l’utilisateur. Activé uniquement par l’utilisateur (interrupteur Notifications de mouvement), notification permanente indiquant l’état de chaque caméra. » |

Vidéo suggérée (moins d’une minute, filmée avec un autre téléphone ou en enregistrement d’écran) : démarrer la surveillance, montrer la notification permanente, passer devant la caméra, montrer l’alerte reçue sur le téléphone spectateur, arrêter la surveillance.

## 10. Autorisations sensibles

Aucune autorisation « à haut risque » (SMS, journal d’appels, localisation en arrière-plan, accès à tous les fichiers) n’est utilisée : pas de formulaire spécifique. Caméra, micro et notifications sont des autorisations standard demandées à l’usage.

## 11. Règlement « logiciels de surveillance » (stalkerware)

Google interdit les applis qui surveillent une personne à son insu. Œil de Lynx est conforme si elle est présentée comme ce qu’elle est :
- caméra de sécurité pour **son propre logement**, avec des appareils appairés par leur propriétaire ;
- **notification permanente** visible pendant toute la surveillance, avec le nombre de spectateurs ;
- l’application n’est **pas cachée** (icône visible) et ne prétend pas espionner qui que ce soit.

La fiche du store est rédigée dans ce sens ; ne pas la présenter comme un outil pour surveiller une autre personne.
