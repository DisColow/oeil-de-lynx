# Œil de Lynx

Application Android de surveillance locale avec clips vidéo et audio déclenchés par mouvement ou son.

## Fonctionnement

- Analyse d'image basse résolution, limitée à environ 1,4 image par seconde.
- Analyse audio locale à 8 kHz lorsque la caméra attend un événement.
- Enregistrement MP4 avec audio lorsqu'un mouvement ou un son déclenche un événement.
- Les clips sont enregistrés dans `Movies/Œil de Lynx` et apparaissent dans la galerie de l'application ainsi que dans les applications photo compatibles.
- Aperçu plein écran, masquable par une couche opaque pendant la surveillance. L'aperçu se referme automatiquement après 1 min 30.
- Galerie plein écran avec miniatures, dates, sélection, suppression et lecture vidéo intégrée.
- Notification à chaque nouveau clip déclenché; toucher la notification ouvre la galerie.
- Service de premier plan; aucune transmission réseau.

La consommation et la température dépendent du téléphone, de l'éclairage et de la caméra. Pour une surveillance prolongée, garder l'appareil ventilé et branché.

## Versions, signature et releases

Chaque tag SemVer `vX.Y.Z` déclenche GitHub Actions et publie un APK signé nommé `oeil-de-lynx-X.Y.Z.apk` dans une GitHub Release.

La clé de signature permanente est sauvegardée dans `.signing/` sur le poste de développement, dossier exclu de Git, et dans les secrets Actions `SIGNING_KEYSTORE_BASE64` et `SIGNING_PASSWORD` du dépôt privé. Conserver une sauvegarde protégée du dossier `.signing/`. Sans cette clé, Android ne pourra pas installer une mise à jour par-dessus une version portant cette signature.

Pour une nouvelle version, incrémenter `versionCode` dans `app/build.gradle.kts`, mettre à jour `versionName`, puis pousser un tag `vX.Y.Z`.

## Compilation locale

Avec JDK 17 et Gradle 8.9 : `gradle assembleDebug`.
