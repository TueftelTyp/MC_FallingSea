# Falling Sea v1.22 (Minecraft 26.3 / Fabric)

**Entwicklungsstand:** Vollständiger Quellcode. Die automatisierten lokalen Modell- und Strukturtests laufen, aber ein Gradle-Build mit Java 25 und echte Minecraft-Spieltests sind hier nicht möglich. Eine fehlerfreie Laufzeit ist daher ausdrücklich nicht bestätigt.

## Korrektur gegenüber v1.13

- Der Scanner nutzt **zwei Warteschlangen**: erst alle Oberflächenlagen über dem Zielpegel, dann die tiefere Strömungsbereinigung. Die bisherige gemeinsame Warteschlange konnte die sichtbare Absenkung durch bis zu 48 Tiefenlagen je Chunk verzögern.
- Eine neu fällige Zielhöhe unterbricht die tiefere Nachbearbeitung, sobald die laufende Oberflächenrunde beendet wurde. Die nächste Oberflächenrunde bekommt Vorrang.
- Bei `dryCaves=false` wird die Meeresoberfläche nicht mehr allein deshalb übersprungen, weil über dem geprüften Block Wasser steht. Die Filterung verwendet jetzt die `OCEAN_FLOOR`-Höhenkarte.
- Status zählt einen Durchgang nicht mehr als abgeschlossen, wenn überhaupt kein Chunk bearbeitet wurde.
- Die übrigen Funktionen (Nachfließen-Schalter, Meeresspiegel/Dürre, Kommandos, Countdown, Debug und Icon) wurden aus v1.13 übernommen.

## Wesentliche Einschränkungen

- **Wasserquelle-Regeneration ist nicht auf Engine-Ebene blockiert.** Das wiederholte Entfernen von Restströmungen garantiert keine dauerhaft trockenen Flächen.
- `DROUGHT` verwendet pro X/Z-Spalte nur eine gespeicherte Wasseroberfläche. Übereinanderliegende Seen können nicht unabhängig austrocknen.
- `GLOBAL` scannt derzeit ebenfalls nur geladene Chunks um Spieler; keine komplette Welt und keine Force-Loads.
- Wasserentfernung ist ohne Welt-Backup nicht rückgängig zu machen. Nicht gemeinsam mit Rising Sea in derselben Welt verwenden.
- `dryCaves=false` orientiert sich an einer Höhenkarte und kann bei besonderen Weltgeometrien vom gewünschten Verhalten abweichen.

## Build

Benötigt JDK 25, Gradle und verfügbare Fabric/Minecraft-Abhängigkeiten:

```sh
gradle clean build
```

Das Build-Ergebnis liegt bei erfolgreicher Kompilierung unter `build/libs/`. Noch keine getestete JAR enthalten.

## Testvorschlag

1. Eine Kopie der Minecraft-Welt verwenden und nur Falling Sea v1.22 installieren.
2. `/fallingsea set debug true` zum Auswerten aktivieren, `/fallingsea mode sea_level`, `/fallingsea set refill false` und `/fallingsea setlevel 60` ausführen.
3. Mit `/fallingsea status` prüfen, ob `Oberflaeche` zügig auf 0 fällt, während `Stroemung` ggf. noch Chunks enthält. `entfernt` sollte bei tatsächlich vorhandenem Wasser oberhalb Y=60 steigen.
4. Wenn `Oberflaeche: 0`, `entfernt: 0` und noch Wasser über dem Zielpegel steht, sind Ausgangshöhe (`startingWaterLevel`), Position/Y-Werte oder die Minecraft-API im Spiel zu prüfen.
5. Nach dem Test `/fallingsea set debug false` setzen.

## Testumfang

`python3 tests/source_checks.py`, `python3 tests/test_priority_regression.py` sowie weitere Tests in `tests/`. Diese Tests prüfen Logikmodelle und Quelltextverträge, keine Minecraft-Laufzeit.


## v1.22 - Hochgelegene Wasserquellen

Bei `SEA_LEVEL` und `suppressInfiniteSources=true` werden nach der priorisierten
Meeresspiegelbearbeitung auch alle Wasserquellen, Wasserstroemungen sowie wasserhaltige
Pflanzen oberhalb von `startingWaterLevel` in geladenen Chunks entfernt. Der
Oberflaechenscan verarbeitet weiterhin den Bereich zwischen `startingWaterLevel` und
aktuellem Zielpegel. Wasserquellen auf/unter der Zielhoehe bleiben unangetastet.

**Achtung:** Das entfernt auch hochgelegene Quellen in Seen und Flussurspruengen.
Die Mod arbeitet auf geladenen Chunks und kann Vanilla-Quellenneubildung noch
nicht direkt an der Engine sperren. Wasser aus Quellen unterhalb der Zielhoehe
kann deshalb weiterhin fliessen. In einer gesicherten Testwelt pruefen.


## v1.22: Wasserreste an Bloecken

Bei aktivem `suppressInfiniteSources` trocknen waterlogged-Quellen oberhalb des Zielpegels automatisch aus, ohne den Tragerblock zu entfernen. Die Stroemungsbereinigung erkennt zusaetzlich fliessendes Wasser in aquatischen Pflanzenbloecken. Quellen auf/unter der Zielhoehe bleiben absichtlich erhalten und koennen Wasser weiter nachliefern; fuer absolut trockene Hoehlen ist eine gesonderte Regel erforderlich.


## v1.22: Chunk-border stability

- Scans the border cells of each horizontal chunk slice before its interior.
- Defers automatic target changes until all discovered surface, elevated-source and flow sweeps finish.
- Hides the countdown when an automatic lowering is delayed by an unfinished scan.
- Does not freeze manual commands; a manual level change remains immediate.

This reduces seams; it cannot guarantee zero temporary water curtains while
Minecraft fluid updates run between scan ticks. Back up worlds before testing.

## v1.22: Wasserberge nach einer Absenkung

Wenn Minecraft durch verzögerte Fluid-Ticks nach dem ersten vollständigen Scan neue
Wasserquellen oder Strömungen erzeugt, wartet Falling Sea bei aktivierter Option
`Nachfliessen verhindern` jeweils 10 Server-Ticks und prüft die Wasseroberfläche
zweimal erneut, bevor ein automatischer Absenkungsschritt als vollständig gilt.
Die Nachprüfungen sind bewusst auf Oberflächenblöcke beschränkt, damit Quellen- und
Tiefenscans nicht für jede Nachprüfung erneut vollständig laufen müssen.
Die Engine kann bei weiter vorhandenen Quellen dennoch neues Fließwasser bilden;
die Nachprüfungen sind eine Reduzierung sichtbarer Reste, keine globale Sperre
aller Fluid-Ticks.

Der Code wurde statisch und mit den Projekt-Modelltests geprüft; ein Minecraft-
Laufzeittest und ein vollständiger Gradle-Build sind nicht Teil dieser Prüfung.

## v1.22 targeted stability changes

- Automatic lowering waits for the visible surface queue, not for the entire elevated-source / deep-flow cleanup, which could stall lowering over large areas.
- Elevated source scan starts at the highest actual WORLD_SURFACE block in each loaded chunk rather than the world build ceiling, avoiding millions of unnecessary air-block checks.
- Existing water removal rules, chunk-border order, Drought mode, settings, debug behavior, icon and HUD remain unchanged.
- Regression: `tests/test_surface_progress.py` tests clock liveness and height boundary handling; existing tests updated to match the new surface-only barrier.

This is a source development build. It has not been compiled against Minecraft 26.3/Fabric here and has not been play-tested. Residual flowing-water mounds may still reappear from sources at/below the waterline; no engine-level fluid-source suppression is implemented.


## v1.22 – adaptive Wasserberg-Nachprüfung

Nach einer Oberflächenbereinigung werden kurz verzögert Nachprüfungen durchgeführt.
Entfernt ein solcher Durchgang erneut Wasser, folgt eine weitere Nachprüfung.
Nach höchstens acht Nachprüfungen stoppt der Durchgang, auch wenn die
Minecraft-Flüssigkeitsphysik weitere Strömungen erzeugt. Der reguläre
Wiedervorlage-Scan bleibt aktiv. Das ist kein Engine-Level-Refill-Blocker.

Die übrige Wasser-, Quellen-, Chunk-, HUD-, Mod-Menu- und Debug-Logik bleibt unverändert.


## Languages (v1.22)

Added de_de and en_us client language resources for config GUI, countdown and translatable server feedback. Existing command names, water simulation and persistence are unchanged.


## v1.22: random Minecraft-day scheduling

`randomSchedule=true` exclusively selects the random day-based interval. The fixed `intervalSeconds` is ignored in this mode. `randomMinDays` and `randomMaxDays` (1..365) choose an inclusive range. One Minecraft day is 24,000 server ticks. The next randomly chosen interval is persisted in `fallingsea-state.json` and survives a restart. Paused/empty-server time does not advance the countdown. Commands: `/fallingsea set randomschedule true|false`, `/fallingsea set randommindays N`, `/fallingsea set randommaxdays N`. The next step remains subject to the existing surface scan readiness condition.
