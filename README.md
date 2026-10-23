# Œil de Lynx

Application Android de surveillance locale avec clips vidéo et audio déclenchés par mouvement ou son.

## Fonctionnement

- Analyse d'image basse résolution, limitée à environ 1,4 image par seconde.
- Analyse audio locale à 8 kHz lorsque la caméra attend un événement.
- Enregistrement MP4 avec audio à partir d'un déclenchement; clips segmentés au plus tard toutes les 10 minutes.
- Stockage privé dans `Android/data/fr.oeildelynx/files/Movies/Evenements`.
- Aperçu caméra de 10 secondes au démarrage, puis masque visuel et surveillance continue.
- Menu Captures pour lister les clips et les ouvrir dans le lecteur vidéo du téléphone.
- Service de premier plan avec notification permanente; aucune transmission réseau.

La consommation et la température dépendent fortement du téléphone, de l'éclairage, du réseau, de la batterie et de la caméra. Une surveillance permanente ne peut pas être garantie sans chauffe sur tous les appareils. Pour une utilisation prolongée, garder l'appareil ventilé et branché, et vérifier les températures et l'arrêt automatique sur le modèle cible. Android peut également arrêter les services selon ses règles d'économie d'énergie.

## Versions et APK GitHub

Chaque tag SemVer `vX.Y.Z` déclenche GitHub Actions et publie un fichier APK nommé `oeil-de-lynx-X.Y.Z.apk` dans une GitHub Release. Incrémenter `versionCode` dans `app/build.gradle.kts` à chaque version et mettre à jour `versionName`, puis pousser un nouveau tag. Pour la première version, utiliser `versionCode = 1`, `versionName = "1.0.0"` et tag `v1.0.0`.

Le workflow génère un APK debug non signé pour faciliter l'installation. Une publication destinée au Play Store nécessitera une configuration de signature dédiée.

## Compilation locale

Avec JDK 17 et Gradle 8.9 : `gradle assembleDebug`.
