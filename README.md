# RealisticSurvival

Plugin de **Minecraft Paper 1.21.x** con mecánicas realistas de supervivencia: deterioro de
alimentos, refrigeración/congelación custom y agricultura estacional con riego activo,
integrado con [RealisticSeasons](https://wiki.realisticseasons.com/) para el clima y la
fecha in-game.

Arquitectura y decisiones de diseño completas en [ARCHITECTURE.md](ARCHITECTURE.md).

## ✨ Funcionalidades

- **Descomposición de alimentos** en decenas (100% → 0%), con 4 fases visuales (Fresco /
  Pasado / Mal Estado / Podrido) reflejadas en la barra de daño del ítem. A 0% el alimento
  se transforma en Restos Podridos.
- **Stacking inteligente**: alimentos del mismo tipo con distinta frescura se fusionan por
  promedio ponderado en vez de rechazarse o separarse.
- **Refrigerador y Congelador** custom (bloque + modelo 3D), con combustible de Hielo /
  Hielo Azul y cálculo 100% pasivo (sin tareas corriendo en segundo plano).
- **Agricultura con riego activo**: se anula la hidratación infinita vanilla; hay que regar
  con Regadera Manual o Aspersor, y la lluvia/temperatura de RealisticSeasons afecta la
  humedad del Farmland (evapora en verano, revierte a Dirt en heladas).
- Todo lo anterior sin ningún `BukkitRunnable` periódico — todas las mecánicas temporales se
  resuelven con **cálculo pasivo (catch-up logic)** al momento de interactuar.

## 📦 Requisitos

- **Paper 1.21.x** (probado con `1.21.10-R0.1-SNAPSHOT`).
- **[RealisticSeasons](https://wiki.realisticseasons.com/)** — dependencia fuerte
  (`depend` en `plugin.yml`): el servidor no cargará el plugin sin ella. RealisticSeasons a
  su vez necesita **ProtocolLib** instalado.
- JDK 21+ para compilar (el `pom.xml` apunta a `release 21`, el mínimo de Paper 1.21.x). El
  bytecode resultante corre sin problema sobre cualquier JVM más nueva usada para el
  servidor (Java 24, 26, etc.) — no hace falta que coincidan.

## 🔨 Compilar

RealisticSeasons no publica un repositorio Maven público, así que su jar se referencia
localmente:

1. Copia tu `RealisticSeasons.jar` a `libs/RealisticSeasons.jar` (ver
   [libs/README.md](libs/README.md)).
2. `mvn package` — genera `target/RealisticSurvival-1.0.0.jar`.

```bash
mvn package
```

## ⌨️ Comandos

| Comando | Permiso | Descripción |
|---|---|---|
| `/rsgive <fridge\|freezer\|wateringcan\|sprinkler> [jugador]` | `realisticsurvival.give` (default `op`) | Entrega un ítem custom directamente — útil para pruebas u operadores, alternativa al crafteo. |

## 🛠️ Recetas de crafteo

Todos los ítems custom se craftean en una mesa de crafteo vanilla y aparecen en el libro de
recetas apenas te conectás. La progresión está pensada para acompañar la dificultad que el
plugin agrega: la Regadera es infraestructura de día 1; Refrigerador y Aspersor son tier
"hierro + redstone básica"; el Congelador queda un escalón arriba porque detiene la
pudrición *por completo* (el Refrigerador solo la retrasa x3).

### Regadera Manual
Barata y temprana — sin ella, el riego activo castiga demasiado desde el arranque.

| | |
|---|---|
| Lingote de Hierro | Lingote de Hierro |
| Botella de Vidrio | Palo |

### Refrigerador
Tier "hierro + redstone básica": el Dropper (7 Piedra + 1 Redstone) va al centro.

| | | |
|---|---|---|
| Hierro | Hierro | Hierro |
| Hierro | **Dropper** | Hierro |
| Hierro | Hierro | Hierro |

### Aspersor
Mismo tier que el Refrigerador pero automatizado — el Dispensador exige además un Arco, un
paso de progresión extra sobre el Dropper. El Balde de Agua vuelve vacío al craftear
(comportamiento vanilla estándar de ítems-contenedor).

| | | |
|---|---|---|
| | Redstone | |
| Hierro | **Dispensador** | Hierro |
| | Balde de Agua | |

### Congelador
Más caro que el Refrigerador porque detiene la pudrición por completo — el Hielo Compacto
obliga a conseguir un pico con Toque de Seda, un paso de progresión real y no solo un gasto
de materiales.

| | | |
|---|---|---|
| Hielo Compacto | Hielo Compacto | Hielo Compacto |
| Hielo Compacto | **Dispensador** | Hielo Compacto |
| Hielo Compacto | Hielo Compacto | Hielo Compacto |

## 🎨 Sin Resource Pack

Refrigerador (Dropper), Congelador (Dispensador) y Aspersor (Dispensador) se ven y
funcionan correctamente aunque el cliente no tenga el Resource Pack cargado: usan esos
Materiales vanilla como base en vez de un lienzo neutro, y el `CustomModelData` (todavía
placeholder) los reemplaza por el modelo 3D real cuando el Resource Pack esté listo. Más
detalle en la sección 10 de [ARCHITECTURE.md](ARCHITECTURE.md).
