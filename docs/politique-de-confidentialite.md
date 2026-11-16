# Politique de confidentialité — Œil de Lynx

*Dernière mise à jour : 16 novembre 2026*

Œil de Lynx transforme un téléphone Android en caméra de surveillance pour **votre propre logement**, et permet de la regarder depuis un autre téléphone que **vous** avez appairé. Cette politique explique quelles données l’application utilise et ce qu’elle en fait.

**En résumé : l’éditeur ne collecte aucune donnée.** L’application n’a ni compte, ni serveur, ni publicité, ni outil de statistiques. Les images, le son et les vidéos restent sur vos téléphones et ne circulent qu’entre vos téléphones appairés, chiffrés de bout en bout.

## 1. Données utilisées sur le téléphone caméra

| Donnée | Utilisation | Où elle va |
| --- | --- | --- |
| Images de la caméra | Détection de mouvement (analysée localement), enregistrement des clips, direct vers vos téléphones spectateurs | Restent sur le téléphone ; envoyées uniquement aux téléphones appairés, chiffrées |
| Son du micro | Piste audio des clips, détection du son (option), son du direct | Idem |
| Clips vidéo | Enregistrés dans `Movies/Œil de Lynx` sur le téléphone | Restent sur le téléphone ; téléchargeables uniquement par un téléphone appairé |
| Niveau de batterie, état de la surveillance | Affichés sur vos téléphones spectateurs | Envoyés uniquement aux téléphones appairés |
| Adresses réseau (Wi-Fi local, IP publique de la box, IPv6) | Permettre au téléphone spectateur de joindre la caméra ; ouverture du port sur votre box via UPnP | Contenues dans le QR code d’appairage et envoyées aux téléphones appairés |

## 2. Données utilisées sur le téléphone spectateur

- **Micro** : uniquement pendant que vous maintenez le bouton « Parler » ; votre voix est envoyée à la caméra appairée, chiffrée, et n’est pas enregistrée par l’application.
- **Caméra** : uniquement pour scanner le QR code d’appairage ; l’image est analysée sur le téléphone et n’est ni enregistrée ni envoyée.
- **Clips téléchargés** : gardés en cache sur le téléphone spectateur (300 Mo au plus) pour être relus.

## 3. Transmission et sécurité

- Les téléphones communiquent **directement** entre eux (réseau Wi-Fi local, ou Internet via votre box), **sans serveur intermédiaire** exploité par l’éditeur ou un tiers.
- L’appairage échange une clé secrète aléatoire de 256 bits par QR code. Tous les échanges (images, son, voix, clips, commandes) sont **chiffrés de bout en bout** (AES-256-GCM) avec cette clé : seuls vos téléphones appairés peuvent les lire. L’éditeur n’a jamais accès à cette clé.
- « Révoquer tous les spectateurs » (téléphone caméra) remplace la clé : les anciens téléphones appairés perdent l’accès.

## 4. Partage avec des tiers

Aucun. L’application ne contient aucun SDK publicitaire, analytique ou de suivi, et ne vend ni ne partage aucune donnée.

## 5. Conservation et suppression

- Les clips restent sur le téléphone caméra jusqu’à ce que vous les supprimiez (galerie de l’application ou application Photos).
- Les réglages et clés sont stockés dans l’application ; désinstaller l’application les efface.
- Les clips mis en cache sur le téléphone spectateur sont effacés automatiquement au-delà de 300 Mo, ou en vidant le cache de l’application.

## 6. Transparence et usage autorisé

Pendant la surveillance, une **notification permanente** est affichée sur le téléphone caméra et indique le nombre de spectateurs connectés ; Android affiche aussi ses propres indicateurs d’utilisation de la caméra et du micro. L’application est destinée à surveiller **votre propre logement**. Filmer ou enregistrer une personne à son insu peut être interdit par la loi : informez les personnes présentes dans les lieux filmés.

## 7. Enfants

L’application ne s’adresse pas aux enfants et ne collecte aucune donnée les concernant.

## 8. Contact

Pour toute question sur cette politique : **[ADRESSE E-MAIL DE CONTACT À COMPLÉTER]**.

## 9. Modifications

Toute modification de cette politique sera publiée sur cette page, avec sa date de mise à jour.
