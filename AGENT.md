# LampeTuya — contexte pour un agent

Application Android minimaliste qui pilote **une** ampoule Tuya en local (Wi-Fi, sans cloud) :
on/off, couleur, blanc chaud↔froid, luminosité, pastilles rapides, et un onglet **Effets**
(5 modes génératifs × 8 palettes, réglables en teinte, vitesse, fluidité, scintillement, luminosité).

Java pur, aucune dépendance (pas d'AndroidX, pas de Kotlin), UI construite en code, APK ≈ 28 Ko.

## ⚠️ Règle absolue

Le compte Tuya contient **deux** ampoules. Seule « Smart Bulb » (`bf43b75f…`) est à nous.
L'autre **appartient à une autre personne : ne jamais lui envoyer de commande,
ne jamais l'ajouter à l'appli, ne jamais stocker sa clé.** Ne pas lancer de test « sur tous les
appareils du compte ».

## Arborescence

| Fichier | Rôle |
|---|---|
| `app/src/main/java/com/lampe/tuya/BulbClient.java` | Client protocole Tuya 3.3 (TCP 6668, AES-128-ECB). Thread d'envoi + thread de lecture, écritures fusionnées par DP (seule la dernière valeur part), heartbeat 10 s, reconnexion auto. |
| `…/EffectEngine.java` | Moteur d'effets, Java pur (testable hors Android). `tick(dt)` → `{h, s, v}`. |
| `…/MainActivity.java` | UI (onglets Lumière / Effets), modèle d'état de l'ampoule, boucle d'effet à 20 images/s, persistance des réglages (SharedPreferences). |
| `…/GradientSlider.java` | Curseur « pilule » à dégradé, libellé dessiné dans la piste. |
| `…/Discovery.java` | Écoute la diffusion UDP 6667 de l'ampoule pour retrouver sa nouvelle IP si le DHCP la change. |
| `tools/bulbtest/` | Banc de test sur PC : compile les vraies classes de l'appli pour la JVM et les fait tourner contre l'ampoule. |
| `bulb.properties` | **Non versionné.** ID, clé locale et IP de l'ampoule, injectés dans `BuildConfig` par Gradle. |

## Compiler

Prérequis (déjà présents sur la machine du propriétaire, installés pour GrvEP) : Android SDK
dans `~/android-sdk` (platform 33, build-tools 33/34), JDK 21 dans `~/jdk21/jdk-21.0.5+11`,
AGP 8.3.2 / Gradle 8.4 en cache.

```bash
cp bulb.properties.example bulb.properties   # puis remplir (voir plus bas)
echo "sdk.dir=$HOME/android-sdk" > local.properties
JAVA_HOME=~/jdk21/jdk-21.0.5+11 ./gradlew --offline assembleRelease
# → app/build/outputs/apk/release/app-release.apk (signé avec la clé debug, pour sideload)
```

## Récupérer la clé locale

Chaque appareil Tuya chiffre ses trames avec une `local_key` de 16 caractères, générée à
l'appairage. Elle ne s'obtient que via le cloud Tuya, **une seule fois** :

1. Projet Cloud sur iot.tuya.com (datacenter Central Europe), app Tuya/Smart Life liée par QR code.
2. `pip install tinytuya`, puis :
   ```python
   import tinytuya
   c = tinytuya.Cloud(apiRegion="eu", apiKey="<Access ID>", apiSecret="<Access Secret>")
   print(c.getdevices())     # → id, key ; ne garder que « Smart Bulb »
   ```
3. `python -m tinytuya scan` donne IP et version du protocole.

**Si l'ampoule est ré-appairée dans l'app Tuya, la clé change** → mettre à jour
`bulb.properties` et recompiler.

## Protocole et points de données (mesurés sur cette ampoule)

Catégorie `dj`, protocole **3.3**. Trame : `000055AA | seq | cmd | len | payload | crc32 | 0000AA55`.
CONTROL (7) = `"3.3" + 12 zéros + AES(json)` ; DP_QUERY (10) et HEART_BEAT (9) = `AES(json)` seul.

| DP | Sens | Valeurs |
|---|---|---|
| 20 | on/off | bool |
| 21 | mode | `"white"` / `"colour"` / `"scene"` |
| 22 | luminosité blanc | 10..1000 |
| 23 | température | 0 (chaud)..1000 (froid) |
| 24 | couleur | `hhhhssssvvvv` hex, h 0..360, s/v 0..1000 |
| 25 | scène native | non utilisé |
| 26 | minuterie | non utilisé |
| 28 | **control_data** (canal « musique ») | `"0"` (saut) + `hhhhssssvvvv` + `00000000` |

L'ampoule n'accepte qu'**une connexion locale à la fois** : si l'app Tuya est ouverte sur le
téléphone, la nôtre peut ne pas se connecter. L'appli coupe sa connexion en `onPause`.

## Le piège principal : ne jamais streamer sur le DP 24

Mesures (flux continu, accusés comptés via les échos de statut) :

- DP 24 en rafale de 3 s : 100 % appliqué jusqu'à ~28/s — **trompeur**.
- DP 24 en continu : l'ampoule **cesse d'appliquer** les commandes après 10 à 40 s, même à
  10/s, alors que le TCP reste ouvert. Une nouvelle connexion la débloque. Cause probable :
  chaque changement du DP 24 est aussi remonté au cloud Tuya et cette file sature.
- **DP 28** : n'est pas stocké, pas d'écho, pas de remontée cloud. 20/s pendant 60 s sur les
  5 modes sans blocage (vérifié visuellement par le propriétaire + DP 24 réactif après coup).

D'où l'architecture actuelle :
- effets et glissement du curseur Couleur → **DP 28** (`sendLive()`) ;
- l'état durable est écrit sur le **DP 24** une seule fois : 300 ms après la fin d'un geste
  (`commitColour`) ou à l'arrêt d'un effet (`stopFx()`).

Comme le DP 28 n'a pas d'écho, on ne peut pas vérifier par logiciel qu'il est appliqué : tout
changement touchant ce canal doit être validé **à l'œil** par le propriétaire.

## Moteur d'effets (`EffectEngine`)

Tous les modes parcourent la palette « étape » par « étape » (phase intégrée, donc changer la
vitesse en cours ne fait pas sauter). `speed` 0..1 → 6 s à 0,1 s par couleur (exponentiel).
`fluidity` = part de l'étape passée en fondu (0 = coupures sèches). `sparkle` = scintillement
de luminosité aléatoire + éclats blancs (désaturation brève), ajoutable à tout mode.

| Mode | Algorithme |
|---|---|
| Fondu | palette dans l'ordre, fondu en fin d'étape |
| Aléatoire | couleur suivante tirée au hasard (jamais deux fois la même) |
| Scintille | fond lent (étape × 4) + scintillement fort (min 0,4) |
| Pulse | une couleur par battement ; fluidité = mélange sinus doux ↔ attaque + décroissance exp. |
| Bougie | dérive lente de teinte (étape × 3) + luminosité en marche aléatoire lissée |

Interpolation de teinte par le plus court arc. Les couleurs à saturation 0 (blanc dans
« Glace ») n'ont pas de teinte : on ne balaie pas à travers. `PRESETS[mode]` = réglages
chargés au choix d'un mode, librement modifiables ensuite. `hueShift` fait tourner toute la palette.

## Tester

Pas de suite de tests automatisée. Méthode éprouvée :

```bash
cd tools/bulbtest && JAVA_HOME=~/jdk21/jdk-21.0.5+11 ./run.sh 12   # secondes par mode
```

Utilise les vraies classes `BulbClient`/`EffectEngine` de l'appli : chaque mode streame sur le
DP 28, puis une écriture DP 24 dont on attend l'écho prouve que l'ampoule n'a pas décroché.
L'ampoule clignote pendant le test : prévenir le propriétaire. Aucun téléphone n'est branché en
adb d'habitude ; l'UI elle-même n'est testable que sur le téléphone du propriétaire.

## Pistes non faites

- Si l'appli passe en arrière-plan pendant un effet, le commit DP 24 peut partir trop tard
  (le client est arrêté juste après) : au retour, l'UI peut afficher la couleur d'avant l'effet.
- Le DP 28 accepte aussi `"1"` (fondu géré par l'ampoule) en premier caractère : non exploité.
- Le Wi-Fi de l'ampoule est faible (ping 70–230 ms) : si des décrochages réapparaissent,
  penser réseau avant de soupçonner le code.
