# Œil de Lynx

Application Android de surveillance locale. Elle surveille les mouvements et les sons, enregistre un clip vidéo avec audio lorsqu’une activité est détectée, puis classe ce clip dans la galerie de l’application et dans les applications photo qui lisent les vidéos de la médiathèque Android.

**Version publiée actuelle : `1.9.4`** — APK signé disponible dans les [releases GitHub](https://github.com/DisColow/oeil-de-lynx/releases).

Oui : on peut présenter le produit comme une première version stable « 1.0 ». Les APKs gardent leur numéro de release réel (`1.9.4` actuellement), car les versions antérieures ont déjà été publiées et Android utilise le numéro technique pour accepter les mises à jour.

## Fonctions

- Aperçu caméra plein écran au lancement. La caméra arrière est sélectionnée par défaut ; le bouton permet de basculer entre caméra avant et arrière.
- Les barres de navigation et d’état Android sont masquées dans l’écran caméra comme dans la galerie. Un balayage depuis le bord les affiche temporairement ; Android les masque ensuite automatiquement.
- Splash screen de lancement et icône adaptative Android assortis : œil de chat vert sur fond vert forêt, avec pupille verticale.
- Démarrage de la surveillance avec aperçu visible pendant 1 minute, puis masquage automatique. Pendant une surveillance active, l’aperçu peut être affiché manuellement ; il se masque de nouveau après 1 min 30.
- Détection locale du mouvement, avec trois niveaux de sensibilité (Réglages). Le déclenchement par le son est une option, désactivée par défaut.
- Enregistrement MP4 avec piste audio dès qu’un mouvement est détecté ; le clip s’arrête 10 secondes après la fin du mouvement. Les durées maximales proposées sont 30 s, 1 min 30, 3 min, 5 min et 10 min.
- Notification Android en priorité haute (bannière et son) sur le téléphone caméra à chaque détection. La toucher ouvre la galerie sur la capture correspondante.
- Galerie plein écran : miniature (image prise à ~1 s du début, chargée en arrière-plan), début et fin du clip, lecture vidéo, sélection multiple et suppression avec confirmation.
- Enregistrement dans `Movies/Œil de Lynx`, donc consultable depuis les applications photo compatibles.
- **Son dans le direct** : le spectateur entend la pièce (bouton Son pour couper), en plus de l’image.
- **Orientation automatique** : posé en portrait, en paysage ou à l’envers, le téléphone caméra envoie une image droite (direct et clips). Bouton « Pivoter » dans le direct en secours.
- **Exposition réglable** (Réglages › Exposition, ou ☀ dans le direct depuis le téléphone spectateur) : image plus claire la nuit, plus sombre face à une fenêtre.
- **Galerie à distance** : depuis le direct, le bouton ▧ liste les clips du téléphone caméra et les télécharge directement pour les lire sur le téléphone spectateur.
- **Alertes sur le téléphone spectateur** : notification dès que la caméra détecte un mouvement (et si elle reste injoignable plus de 3 minutes), sans serveur de notifications.
- **Direct vers un second téléphone** : un téléphone spectateur appairé par QR code regarde la caméra en temps réel, sur le même Wi-Fi ou depuis Internet. Le flux va directement du téléphone caméra au spectateur via la box internet de la caméra, sans serveur intermédiaire ; il est chiffré de bout en bout avec la clé échangée lors de l’appairage.
- Service de premier plan pendant la surveillance. Les analyses et captures restent locales ; l’application n’envoie de vidéo qu’aux téléphones spectateurs appairés, directement.

## Comportement et limites

Le détecteur vidéo analyse le flux d’analyse CameraX (640 × 480 visé, pour servir aussi au direct) toutes les 500 ms. Il compare une grille d’environ 40 × 30 points, chacun moyenné sur 2 × 2 pixels pour atténuer le grain. L’écart médian entre deux images est d’abord retiré, ce qui neutralise les variations globales de luminosité (exposition automatique, nuage) ; un point est « modifié » s’il change ensuite de plus de 25 niveaux. Il y a mouvement si au moins 5 % (faible), 2 % (normale, par défaut) ou 0,8 % (élevée) des points changent : une personne qui n’occupe qu’une petite partie du cadre suffit. Un saut de luminosité très fort (lumière allumée ou éteinte) compte aussi comme une activité.

Un clip démarre au premier mouvement détecté. Chaque nouveau mouvement repousse sa fin ; il s’arrête 10 secondes après le dernier mouvement, ou à la durée maximale choisie — si le mouvement continue, un nouveau clip démarre aussitôt. Un seul clip est enregistré à la fois. Le clip commence au moment de la détection : les instants qui la précèdent ne sont pas enregistrés. Option « Déclencher aussi sur le son » : le détecteur sonore lit le microphone en mono à 8 kHz et déclenche sur un niveau RMS fixe ; pendant un clip le micro sert à la piste audio, c’est donc l’absence de mouvement qui termine le clip.

**Masquer l’aperçu cache l’image dans l’interface, mais ne coupe pas la caméra ni l’analyse.** La caméra reste nécessaire à la détection de mouvement. Le masquage visuel ne garantit donc pas à lui seul une baisse de température. La consommation et la chauffe varient selon le téléphone, la température ambiante, l’alimentation et la scène filmée. Vérifier le comportement sur le T771K lors d’une surveillance prolongée et laisser l’appareil ventilé.

## Direct entre deux téléphones

Le même APK sert aux deux rôles : le **téléphone caméra** (celui qui surveille) et le **téléphone spectateur** (celui qui regarde).

### Appairer

1. Sur le téléphone caméra : Réglages › **Appairer un téléphone spectateur**. Un QR code s’affiche, avec l’état de la box et les adresses où la caméra sera joignable.
2. Sur le téléphone spectateur : Réglages › **Regarder une caméra** (ou appui long sur l’icône › **Regarder**), puis **Scanner le QR code d’une caméra**. À défaut, le code peut être envoyé par message (« Partager le code ») puis collé.
3. Démarrer la surveillance sur le téléphone caméra. Sur le spectateur, toucher le nom de la caméra : le direct s’affiche (environ 8 images/s en 640 × 480, sans son), avec l’indicateur REC lorsqu’un clip est en cours d’enregistrement.

Le direct n’existe que pendant la surveillance : c’est le service de surveillance qui possède la caméra. Tant qu’aucun appairage n’a été créé, l’application n’ouvre aucun port. La notification de surveillance et la pastille du HUD indiquent le nombre de spectateurs connectés (3 au maximum).

### Chemin réseau, sans serveur

Le téléphone caméra écoute en TCP sur le port **47474**. Le spectateur essaie en parallèle toutes les adresses connues et garde la première qui répond :

| Situation du spectateur | Adresse utilisée | Condition |
| --- | --- | --- |
| Même Wi-Fi que la caméra | IPv4 locale (`192.168.x.x`) | Aucune. |
| En 4G / autre réseau | IPv4 publique de la box | Port 47474 redirigé vers le téléphone caméra. L’application le demande automatiquement à la box en **UPnP** au démarrage de la surveillance et le referme à l’arrêt. Si la box n’a pas l’UPnP (ou s’il est désactivé), créer la redirection TCP 47474 → IP locale du téléphone caméra dans l’interface de la box, idéalement avec un bail DHCP fixe. |
| En 4G / autre réseau, en IPv6 | IPv6 globale du téléphone caméra | Le pare-feu IPv6 de la box doit autoriser les connexions entrantes (souvent désactivé par défaut). |
| IP publique changeante | Nom DynDNS saisi dans « Adresse personnalisée » | Ex. nom fourni par la box (`xxx.freeboxos.fr`, DynDNS de la Livebox…). |

Les adresses sont enregistrées dans le QR code puis **mises à jour automatiquement** à chaque connexion réussie (par exemple sur le Wi-Fi de la maison). Si la box est elle-même derrière un autre routeur ou une IP partagée par l’opérateur (CGNAT, adresses `100.64.x.x`), l’accès IPv4 depuis Internet est impossible : l’écran d’appairage le signale ; restent le même Wi-Fi et l’IPv6.

### Sécurité

- Le QR code contient une clé secrète aléatoire de 256 bits propre à la caméra. Ne le montrer qu’à des téléphones de confiance.
- À chaque connexion, les deux téléphones échangent des nonces aléatoires et dérivent deux clés AES-256-GCM (HMAC-SHA256). L’authentification du spectateur, les images et les états sont chiffrés et authentifiés ; un enregistrement modifié, rejoué ou envoyé avec une autre clé est refusé et la connexion fermée. Rien n’est envoyé en clair avant l’authentification, hormis l’en-tête de protocole et les nonces.
- **Révoquer tous les spectateurs** (écran d’appairage) génère une nouvelle clé et déconnecte immédiatement les spectateurs : ils doivent scanner le nouveau QR code.
- Il s’agit d’un protocole spécifique construit sur des primitives standard (pas de TLS) ; il n’a pas fait l’objet d’un audit indépendant.

### Son et orientation du direct

- **Son** : le téléphone caméra ouvre son micro uniquement quand un spectateur écoute, et l’envoie en G.711 µ-law 16 kHz (≈ 128 kbit/s, en plus des 2 à 3 Mbit/s de l’image), chiffré comme le reste. Le bouton **Son** du direct le coupe ou le rétablit ; le choix est mémorisé par caméra. Au lancement d’un clip, le micro du direct est suspendu un instant pour laisser CameraX ouvrir la piste audio du clip, puis rouvert. *À vérifier sur chaque téléphone : certains Android peuvent rendre muet l’un des deux usages simultanés du micro ; la piste du clip reste prioritaire.*
- **Orientation** : le service de surveillance suit la position physique du téléphone (capteur d’orientation) et l’applique à l’image du direct (immédiatement) et aux clips (au prochain clip). À plat sur une table, la dernière position reconnue est conservée.
- **Lecture des clips** : le lecteur des deux galeries occupe tout l’écran en gardant les proportions, avec un bouton « Pivoter » pour redresser les clips enregistrés couchés par les versions antérieures à 1.9.0.
- **Pivoter** : dans le direct, tourne l’image d’un quart de tour par appui, mémorisé par caméra, si un téléphone signale mal sa position.

### Exposition et galerie depuis le téléphone spectateur

Dans l’écran du direct :

- **☀** règle la correction d’exposition de la caméra (curseur en EV, bouton « Auto » pour revenir à 0). Le réglage est enregistré sur le téléphone caméra et réappliqué à chaque démarrage ; il se règle aussi sur place par Réglages › Exposition.
- **▧** ouvre la galerie de la caméra : liste des 200 derniers clips avec miniatures, puis téléchargement du clip touché par la même connexion directe et chiffrée, et lecture sur le téléphone spectateur. Pendant le téléchargement, un panneau affiche le pourcentage, la quantité reçue, la vitesse (moyenne sur les 3 dernières secondes) et le temps restant estimé, avec un bouton Annuler ; tourner le téléphone ne l’interrompt pas. Les clips téléchargés sont gardés en cache (300 Mo au plus) et ne sont pas retéléchargés. Un clip SD pèse environ 10 à 20 Mo par minute : en 4G, le téléchargement consomme le forfait du spectateur et l’envoi montant de la box.

Comme le direct, la galerie à distance n’est accessible que pendant la surveillance (c’est le service de surveillance qui répond), et la caméra doit être en version 1.7.0 ou plus.

### Alertes de mouvement sur le téléphone spectateur

Sur le téléphone spectateur, les notifications sont proposées dès l’appairage ; ensuite, le bouton **🔔 Notifications de mouvement** sous chaque caméra (Réglages › Regarder une caméra) les active ou les coupe. Android demande l’autorisation d’afficher des notifications. Le téléphone garde alors une connexion directe et légère avec la caméra, sans image (un signe de vie toutes les 25 s), et affiche :

- une notification « Mouvement détecté · *caméra* » au début de chaque clip ; la toucher ouvre le direct ;
- une notification si la caméra reste injoignable plus de 3 minutes (surveillance arrêtée, Wi-Fi ou box coupés…).

Comme il n’y a pas de serveur de notifications (type Firebase), c’est un service de premier plan du téléphone spectateur qui maintient la connexion ; une notification discrète indique l’état de chaque caméra. Il redémarre avec le téléphone. Les mêmes conditions réseau que le direct s’appliquent ; exclure l’application de l’optimisation de batterie sur le téléphone spectateur pour éviter qu’Android ne coupe la connexion. Jusqu’à 5 téléphones peuvent recevoir les alertes d’une caméra, en plus des 3 spectateurs du direct.

### Débit, batterie et limites

- Les images JPEG ne sont encodées que lorsqu’au moins un spectateur est connecté. Compter environ 2 à 3 Mbit/s en montant depuis la box : confortable en fibre, juste en ADSL. Sur un lien lent, le serveur saute les images intermédiaires au lieu d’accumuler du retard.
- Pendant le direct, un verrou Wi-Fi empêche la mise en veille du Wi-Fi écran éteint. Sur certains téléphones, il faut aussi exclure l’application de l’optimisation de batterie.
- Pas de son dans le direct pour l’instant ; les clips enregistrés gardent leur piste audio.
- Le spectateur coupe le flux lorsqu’il quitte l’écran du direct ; il se reconnecte automatiquement en cas de coupure.

## Architecture du projet

L’interface est construite en Kotlin avec des vues Android créées directement dans le code (pas de fichiers de layout XML).

| Fichier | Rôle |
| --- | --- |
| `MainActivity.kt` | HUD caméra, permissions, aperçu, réglages, état de surveillance et démarrage/arrêt du service. |
| `MonitorService.kt` | Service de premier plan, session CameraX, détections audio/vidéo, enregistrement MediaStore et notifications. |
| `GalleryActivity.kt` | Liste des vidéos, miniatures, sélection, suppression confirmée et lecteur intégré. |
| `TransferStats.kt` | Vitesse, quantité reçue et temps restant des téléchargements de la galerie distante. |
| `ClipPlayer.kt` | Lecteur vidéo des galeries (TextureView) : plein écran proportionné et rotation par quart de tour. |
| `DeviceOrientation.kt` | Position physique du téléphone caméra → rotation des images (direct et clips). |
| `LynxUi.kt` | Système visuel commun : couleurs, typographie, boutons, cartes, pastilles d’état, feuille de menu. |
| `app/src/main/res/drawable/ic_*.xml` | Jeu d’icônes vectorielles de l’interface (même trait que l’icône caméra). |
| `ImmersiveMode.kt` | Masquage temporaire des barres de navigation et d’état dans les écrans principaux. |
| `LiveProtocol.kt` | Protocole du direct (échange de nonces, canal AES-GCM, code d’appairage). Pur JVM, testé. |
| `LiveServer.kt` | Serveur TCP du direct dans le téléphone caméra ; une file « dernière image » par spectateur. |
| `LiveFrameEncoder.kt` | Conversion des images YUV d’`ImageAnalysis` en JPEG pour le direct. |
| `LiveCamera.kt` | Identité et clé de la caméra, adresses publiables (IPv4, IPv6, DynDNS), ouverture du port sur la box. |
| `LiveUpnp.kt` | Client UPnP IGD minimal (SSDP, `AddPortMapping`, IP externe) écrit sur sockets bruts. |
| `LiveClient.kt` | Connexion du spectateur (course entre adresses, reconnexion) et stockage des caméras appairées. |
| `PairingActivity.kt` | Écran caméra : QR code, état de la box, adresses, DynDNS, révocation. |
| `Exposure.kt` | Correction d’exposition mémorisée et appliquée à CameraX (aperçu, surveillance, commande distante). |
| `CaptureLibrary.kt` | Liste, miniatures et lecture des clips pour la galerie distante (dossier de l’application seulement). |
| `RemoteGalleryActivity.kt` | Galerie de la caméra consultée et téléchargée depuis le téléphone spectateur. |
| `AlertService.kt` | Téléphone spectateur : connexion d’alerte permanente et notifications de mouvement, relance au démarrage. |
| `ViewerActivity.kt` / `ScanActivity.kt` / `LiveActivity.kt` | Écrans spectateur : caméras appairées, lecteur de QR code (ZXing), direct plein écran. |
| `app/src/main/res/drawable/ic_camera*.xml` | Icônes caméra visible et caméra masquée. |
| `app/src/main/res/drawable/ic_cat_eye_*.xml` | Visuel vectoriel de l’œil pour le splash screen, l’icône et les icônes thématiques Android. |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` | Icônes adaptatives du lanceur Android. |
| `app/src/main/res/values/styles.xml` | Thème de l’application et thème AndroidX du splash screen. |
| `app/src/main/AndroidManifest.xml` | Activités, service caméra/micro, permissions (dont réseau pour le direct) et `FileProvider`. |
| `app/src/main/res/xml/shortcuts.xml` | Raccourci « Regarder » sur l’icône du lanceur. |
| `app/src/main/res/xml/file_paths.xml` | Chemins privés exposés au lecteur Android pour les anciens clips. |
| `.github/workflows/release.yml` | Compilation et publication de l’APK signé à chaque tag de version. |

### Cycle d’une capture

1. `MainActivity` demande les permissions caméra, microphone et notifications, puis démarre `MonitorService`.
2. Le service lie ensemble CameraX `Preview`, `ImageAnalysis` et `VideoCapture` sur la caméra choisie, puis confirme son état à l’interface. Si la liaison CameraX échoue, le HUD revient à l’état prêt et explique l’erreur au lieu d’afficher une surveillance active fictive.
3. Le détecteur vidéo échantillonne les images sur un exécuteur dédié ; le détecteur audio lit le microphone sur son propre thread.
4. Au premier déclencheur, le service démarre une capture audio/vidéo dans `Movies/Œil de Lynx`. Les nouveaux mouvements repoussent la fin du clip courant au lieu d’en créer un second ; l’analyse du son reprend à la fin du clip.
5. À la fin, le service publie/met à jour la notification. La galerie interroge `MediaStore` pour afficher la vidéo et la rendre lisible dans Photos.

La galerie sait aussi lire les clips des anciennes versions depuis le répertoire privé `Movies/Evenements` de l’application. Un changement de caméra pendant la surveillance relie à nouveau les cas d’usage CameraX dans le service actif ; si un clip est en cours, il est finalisé avant la bascule. Si la caméra demandée est indisponible, le service tente de revenir à la précédente.

## Prérequis de développement

- JDK 17
- Android SDK avec les plateformes/build tools nécessaires pour `compileSdk 35`
- Gradle 8.9
- Un appareil Android API 29 ou plus, ou un émulateur. Caméra, microphone et notifications doivent être autorisés pour surveiller.

Le dépôt ne contient pas de Gradle Wrapper ; la commande `gradle` doit donc être disponible sur le poste.

### Compiler et installer une version de développement

Depuis la racine du dépôt :

```shell
gradle assembleDebug
```

Pour exécuter les tests unitaires du détecteur de mouvement et l’analyse Android Lint avant de compiler :

```shell
gradle testDebugUnitTest lintDebug assembleDebug
```

Les tests du direct (`LiveProtocolTest`, `LiveUpnpTest`) vérifient l’échange chiffré sur de vrais sockets locaux, le refus d’une mauvaise clé, d’un enregistrement modifié ou rejoué, le code d’appairage et l’analyse des réponses UPnP. Ils ne remplacent pas un essai réel entre deux téléphones, en Wi-Fi puis en 4G.

Les tests du détecteur couvrent une image stable, une faible variation d’éclairage, une modification sous le ratio requis, un changement net de scène, ainsi que les images vides ou de tailles différentes. Ils valident la logique de seuil ; ils ne remplacent pas un essai caméra et température sur le T771K.

L’APK de développement est créé ici :

```text
app/build/outputs/apk/debug/app-debug.apk
```

Pour l’installer sur un appareil connecté avec ADB :

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Le build debug utilise la signature de développement Android et ne remplace pas l’APK release signé.

## Versions et releases signées

Les versions publiées suivent `X.Y.Z`. **Ne réutilisez jamais un tag existant et ne revenez pas à `1.0.0`** : le `versionCode` est calculé à partir du tag et doit augmenter pour qu’Android accepte une mise à jour par-dessus la version installée.

La release est entièrement compilée par GitHub Actions. Depuis un commit prêt à publier sur `main` :

```shell
git tag v1.9.4
git push origin v1.9.4
```

Remplacer `1.9.4` par le prochain numéro non utilisé. Le workflow extrait le numéro depuis le tag, exécute les tests unitaires et Android Lint, compile `assembleRelease`, signe l’APK et le joint à une GitHub Release sous le nom `oeil-de-lynx-X.Y.Z.apk` (APK seul, pas d’archive ZIP).

Le workflow attend deux secrets Actions au niveau du dépôt :

- `SIGNING_KEYSTORE_BASE64` : contenu Base64 du keystore de release `.p12`.
- `SIGNING_PASSWORD` : mot de passe du keystore.

L’alias de signature configuré dans Gradle est `oeil-de-lynx-release`. Le keystore et les informations permettant de le récupérer sont conservés hors Git dans `.signing/` sur le poste de développement. Garder une sauvegarde chiffrée et protégée : **toutes les mises à jour installées par-dessus l’application doivent utiliser la même clé**. Ne jamais committer le keystore, son mot de passe, ni un token GitHub.

Une fois le tag poussé, suivre l’exécution dans l’onglet **Actions** du dépôt. La publication est terminée lorsque le job réussit et que l’APK versionné apparaît dans **Releases**.

## Permissions et stockage

Le manifeste déclare les permissions caméra, microphone, notifications Android 13+, service de premier plan caméra/micro et verrouillage d’activité, ainsi que les permissions réseau du direct (Internet, état du réseau et du Wi-Fi). Le téléphone spectateur n’a besoin que de la caméra, pour scanner le QR code. L’utilisateur doit accepter les permissions runtime caméra, microphone et notifications avant de démarrer la surveillance.

Les nouveaux clips sont publiés via `MediaStore` sous `Movies/Œil de Lynx`. La suppression dans la galerie de l’application supprime le média correspondant. Les clips conservés dans le dossier privé historique ne sont visibles que par l’application.

## Vérifications avant reprise ou publication

- Compiler un build debug avec `gradle assembleDebug`.
- Installer l’APK release de test sur un appareil de test ; vérifier l’installation de mise à jour avec la même clé de signature.
- Vérifier séparément splash, icône du lanceur, aperçu, masquage, bascule de caméra pendant la surveillance et pendant un clip, détection son/mouvement, notification, durée maximale, lecture et suppression.
- Direct : appairage par QR code, direct sur le même Wi-Fi, puis en 4G (vérifier l’ouverture UPnP ou la redirection manuelle), REC visible côté spectateur, révocation, reconnexion après coupure Wi-Fi.
- Faire un essai prolongé sur le téléphone cible : l’émulateur ne reproduit ni les performances de caméra ni la température du T771K.
- Avant une release, contrôler le tag, le succès du workflow Actions et la présence de l’APK dans la release GitHub.
