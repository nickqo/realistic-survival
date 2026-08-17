# libs/

RealisticSeasons no publica un repositorio Maven público, así que su API se referencia como
dependencia local (`scope=system`) en el [pom.xml](../pom.xml).

Coloca aquí el jar de RealisticSeasons descargado (SpigotMC / Polymart / BuiltByBit) con el
nombre exacto:

```
libs/RealisticSeasons.jar
```

Este archivo **no se versiona en git** (ver `.gitignore`), cada desarrollador debe colocar su
propia copia localmente. En el servidor de producción, el jar real de RealisticSeasons debe
instalarse en la carpeta `plugins/` de Paper — `RealisticSurvival` lo declara como dependencia
fuerte (`depend`) en `plugin.yml`.

Ver la sección 8 de [ARCHITECTURE.md](../ARCHITECTURE.md) para más detalles.
