# 🗺️ Documento de Arquitectura y Contexto: Plugin "RealisticSurvival" v1.0

## 📌 Visión General
"RealisticSurvival" es un plugin inmersivo que introduce mecánicas avanzadas de deterioro de alimentos, refrigeración custom y agricultura estacional realista utilizando objetos y bloques vanilla.

## ⚙️ 1. Motor Base y Manejo del Tiempo (Catch-up Logic)
El plugin depende de RealisticSeasons (RS) para el clima y la fecha, abstrayendo esta conexión para no acoplar el resto del código.

- Abstracción (Interfaces): Todo el sistema le pide la hora a una interfaz `TimeProvider`. La clase `RSTimeHandler` implementa esta interfaz consultando la API de RS.
- Unidad de Medida: El tiempo se mide y guarda en "Días In-Game absolutos", **con precisión de hora** (`double`, ej. `572.34`) — no solo el número de día calendario. Se combina `Calendar#getTotalDays` (día entero) con `SeasonsAPI#getHours/getMinutes/getSeconds` (hora del día actual) para que la fracción decimal represente cuánto del día actual transcurrió.
  - **Por qué:** si solo se midiera en días enteros, la pudrición quedaría "congelada" durante todo el día y saltaría de golpe recién al cruzar la medianoche in-game — nada progresivo, y particularmente mal ya que `RealisticSeasons` permite alargar/acortar la duración real de un día in-game por configuración. Con precisión de hora, una diferencia de medio día in-game (`0.5`) ya produce la mitad del deterioro correspondiente, sin importar cuántos minutos reales dure ese medio día.
- Almacenamiento de Estado: Se guarda el `timestamp_creacion` o `ultimo_calculo` (como `double`, vía `PersistentDataType.DOUBLE`) en el PersistentDataContainer (PDC) de los bloques y en los DataComponentTypes custom de los ítems.
- Cálculo Pasivo: Cuando un jugador abre un contenedor o interactúa con un ítem, el sistema resta el día actual menos el día guardado en los datos del objeto, ajustando el deterioro según la temperatura antes de mostrar el resultado.
- Recursos discretos (Hielo, Cubos de Agua): a diferencia de la frescura (que es continua), el hielo/las cargas de agua no se pueden consumir en fracciones — un electrodoméstico/Aspersor con un tramo parcial de días fríos redondea el consumo **hacia arriba** (menos favorable, nunca deja un tramo sin proteger a costa de "ahorrar" combustible).

## 🥩 2. Sistema de Descomposición de Alimentos (Spoilage Tiers)
El sistema interno maneja la frescura de la comida utilizando estrictamente decenas enteras (100, 90, 80, 70... 0%). Cualquier cálculo interno debe redondearse siempre a la decena más cercana.

Visualmente, el desgaste se muestra inyectando los componentes `minecraft:max_damage` y `minecraft:damage` al alimento, dividiéndolo en 4 fases:

- Fresco (80%, 90%, 100%): Verde (Textura normal). 100% alimento y saturación vanilla.
- Pasado (40%, 50%, 60%, 70%): Amarilla. -30/40% de alimento, baja saturación.
- Mal Estado (10%, 20%, 30%): Roja. Recuperación mínima. Efectos: Hambre I, posibles Náuseas.
- Podrido (0%): Sin barra (Transformado). Ítem transformado a "Restos Podridos". Efectos: Veneno I, Hambre II. Útil para el compostador.

## 📦 3. Lógica de Inventario y Stacking (Merge)
En la 1.21, Minecraft rechaza agrupar (stackear) ítems si sus Componentes o PDC no son exactamente iguales. Debemos interceptar el `InventoryClickEvent` y el `EntityPickupItemEvent` para fusionar alimentos del mismo tipo pero con distinta frescura.

- Promedio Ponderado Matemática: Al intentar juntar dos stacks, se calcula la nueva frescura basándose en la cantidad aportada por cada stack.
- Fórmula Base: `Nueva_Frescura = ((Cant_A * Frescura_A) + (Cant_B * Frescura_B)) / (Cant_A + Cant_B)`
- Redondeo Obligatorio: El resultado de la fórmula se redondea a la decena más cercana (ej. 74% pasa a 70%, 76% pasa a 80%).
- Ejecución: Se cancela el evento vanilla, se elimina el ítem del cursor y se actualiza el `ItemStack` en el inventario con el nuevo porcentaje unificado y su respectivo Damage visual.

## ❄️ 4. Electrodomésticos: Refrigerador y Congelador (Motor Custom)
Se crean bloques interactivos sin depender de plugins de texturas de pago.

### 4.1. Implementación Física y Visual (Cero Lag)
- Base Física: Al colocar el ítem, se coloca un bloque de BARRIER (Barrera) invisible para proveer una hitbox sólida.
- Renderizado 3D: En el centro exacto de la Barrera, se invoca una entidad `ItemDisplay` que muestra un modelo 3D usando el `CustomModelData` de un Resource Pack.
- Inventario: Al hacer clic derecho en la Barrera, se abre un Inventario Virtual (GUI de 9 slots). El contenido se serializa y guarda en el PDC del Chunk (asociado a las coordenadas).

### 4.2. Combustible y Catch-up Logic
- Combustible: El Slot 8 está reservado para Hielo o Hielo Azul.
- Sin Ticking: Al cerrar la interfaz o al descargarse el chunk, se guarda el dato `ultimo_dia_calculado`. No hay procesos corriendo mientras está cerrado.
- Cálculo al Abrir: Al reabrir, el código calcula los días pasados, resta la durabilidad del Hielo. Si el hielo se acabó durante ese periodo de tiempo, calcula la pudrición de la comida combinando el "tiempo frío" y el "tiempo a temperatura ambiente".

### 4.3. Efectos Térmicos
- Refrigerador: Multiplica x3 o x4 la duración del alimento.
- Congelador: Detiene la pudrición por completo. Inyecta el componente/tag custom `frozen: true` al ítem. Penalización: Si el jugador cocina o come un ítem con `frozen: true`, sus valores nutricionales caen al 20%. Para quitar el estado, el ítem debe estar en un inventario a temperatura ambiente durante X tiempo.

## 🌱 5. Agricultura Estacional y Riego (Cultivos Vanilla)
El plugin anula la mecánica de agua infinita vanilla (9x9) de Minecraft para obligar al jugador a usar riego activo, integrado con el clima de RealisticSeasons.

### 5.1. Dinámica de Humedad del Farmland
- Interceptación: Se cancelan los eventos vanilla que hidratan el bloque de tierra de cultivo (Farmland).
- Lluvia (RS API): Los bloques de Farmland expuestos al cielo restauran su nivel de humedad al máximo (nivel 7) cuando llueve.
- Evaporación Térmica: En verano, la humedad disminuye más rápido. En invierno, si la temperatura baja de 0°C, la tierra húmeda se revierte a bloque de Dirt, destruyendo el cultivo plantado.

### 5.2. Herramientas de Riego
- Regadera Manual: Ítem con `CustomModelData`. Se recarga con clic derecho en una fuente de agua. Al usarse sobre cultivos, hidrata el Farmland en un radio de 3x3 al nivel 7.
- Aspersor: Bloque custom (sistema Barrier + ItemDisplay). Contiene un inventario para cargar cubetas de agua. Hidrata un área de 5x5 de forma pasiva (usando la misma lógica Catch-up explicada en los electrodomésticos).

### 5.3. Estrés Hídrico y Muerte de Plantas
- Un cultivo plantado en un Farmland con humedad 0 entra en "Estrés Hídrico".
- Usando lógica Catch-up, si un bloque se carga y se detecta que ha pasado X días in-game en estrés hídrico, la planta retrocederá una fase de crecimiento o morirá (reemplazándose por un Dead Bush).

## 🛠️ 6. Estructura Obligatoria de Paquetes Java

```
src/main/java/cl/nico/realisticsurvival/
├── RealisticSurvival.java        # Main class (Registro de eventos y comandos)
├── api/
│   └── time/
│       ├── TimeProvider.java     # Interfaz abstracta para inyección de dependencias
│       └── RSTimeHandler.java    # Implementación puente con RealisticSeasons
├── food/
│   ├── FoodManager.java          # Lógica de decenas, manejo de DataComponentTypes (1.21)
│   └── ConsumeListener.java      # Intercepta PlayerItemConsumeEvent y aplica efectos
├── inventory/
│   └── InventoryListener.java    # Lógica de stacking (InventoryClickEvent), promedio y redondeo
├── appliances/
│   ├── ApplianceManager.java     # Manejo de Barriers, ItemDisplays (spawn/despawn)
│   ├── ApplianceGUI.java         # Interfaces virtuales de 9 slots
│   └── CatchUpProcessor.java     # Cálculo de hielo y pudrición offline
└── farming/
    ├── FarmingListener.java      # Anula eventos vanilla (BlockFadeEvent, BlockGrowEvent)
    └── WateringManager.java      # Lógica de la regadera manual y aspersores
```

> Nota: el paquete raíz utilizado en la implementación es **`cl.nico.realisticsurvival`** (en vez de `com.tuusuario.realisticsurvival`).

## 🚫 7. Reglas de Desarrollo Obligatorias

Estas reglas aplican a **todo** el código del proyecto, sin excepciones:

1. **Prohibido NBT antiguo.** No se permite `net.minecraft.server`, `NBTTagCompound` ni librerías NBT de terceros (NBT-API, etc.). Toda persistencia de datos custom debe usar `PersistentDataContainer` (PDC) y toda visualización de propiedades vanilla (daño, nombre, modelo custom, etc.) debe usar los `DataComponentTypes` nativos de la API de Paper 1.21.
2. **Arquitectura modular estricta (SRP).** Cada clase tiene una única responsabilidad: la lógica matemática/cálculo (managers/processors) vive separada de los listeners de eventos de Bukkit, y estos a su vez están separados de los controladores puramente visuales (GUIs, displays). No se debe mezclar cálculo de negocio dentro de un listener.
3. **Cero ticking activo.** Prohibido usar `BukkitRunnable`/`Scheduler` repetitivo para revisar bloques, inventarios o ítems en segundo plano. Toda mecánica temporal (pudrición, hielo, humedad, estrés hídrico) se resuelve mediante **cálculo pasivo (catch-up logic)**: se guarda un timestamp (día in-game) al cerrar/guardar el estado, y se recalcula la diferencia únicamente en el momento en que el jugador vuelve a interactuar (abrir GUI, hacer clic, cargar el chunk, etc.).
4. **Dependencia fuerte (hard dependency) con RealisticSeasons.** El plugin no debe arrancar sin RealisticSeasons presente; toda la información de clima y fecha in-game se obtiene exclusivamente a través de la abstracción `TimeProvider` / `RSTimeHandler`, nunca accediendo directamente a la API de RS desde otras clases.

## 🧱 8. Notas de Build (RealisticSeasons como dependencia)

RealisticSeasons **no publica un repositorio Maven público** (su propia documentación indica "Maven is still a work in progress"). Por lo tanto, para poder compilar contra su API:

1. Descarga el `.jar` de RealisticSeasons (SpigotMC / Polymart / BuiltByBit, según tu licencia) y colócalo en la carpeta `libs/` del proyecto como `RealisticSeasons.jar`.
2. El `pom.xml` referencia ese `.jar` como una dependencia de **scope `system`**, apuntando a `${project.basedir}/libs/RealisticSeasons.jar`.
3. En tiempo de ejecución el `.jar` real de RealisticSeasons debe estar en la carpeta `plugins/` del servidor Paper; `RealisticSurvival` lo declara como `depend` (hard dependency) en `plugin.yml`, por lo que el servidor no cargará nuestro plugin si RealisticSeasons no está presente.
4. Alternativa recomendada a futuro: instalar el jar en el repositorio local de Maven con `mvn install:install-file` y usar una dependencia normal en vez de `system` scope (que está deprecado en Maven 4).
5. El servidor Paper de destino requiere **Java 26** para ejecutarse (build de Paper usado por el usuario); el plugin igual se compila con `maven.compiler.release=21` (mínimo de Paper 1.21.x) — el bytecode 21 corre sin problema sobre un JVM 26.

## 🎁 9. Obtención de ítems custom

### 9.1. Recetas de crafteo

`recipes/RecipeManager.java` registra una `ShapedRecipe` por cada ítem custom (mesa de crafteo vanilla) y las "descubre" automáticamente para cada jugador al conectarse (`PlayerJoinEvent` → aparecen en el libro de recetas). Progresión pensada para acompañar la dificultad que el resto del plugin ya agrega:

| Ítem | Receta | Por qué |
|---|---|---|
| Regadera Manual | 2 Lingote de Hierro + Botella de Vidrio + Palo | Barata y temprana: sin ella, el riego activo (sección 5) castiga demasiado desde el día 1. |
| Refrigerador | Dropper + 8 Lingote de Hierro (Dropper al centro) | Tier "hierro + redstone básica" — Dropper = 7 Piedra + 1 Redstone. |
| Aspersor | Dispensador + 2 Lingote de Hierro + 1 Redstone + 1 Balde de Agua | Mismo tier que el Refrigerador pero automatizado: el Dispensador exige además un Arco (paso extra sobre el Dropper). El balde vuelve vacío al craftear (comportamiento vanilla estándar de ítems-contenedor). |
| Congelador | Dispensador + 8 Hielo Compacto (Dispensador al centro) | Más caro que el Refrigerador porque detiene la pudrición **por completo** (el Refrigerador solo la retrasa x3) — el Hielo Compacto obliga a conseguir Toque de Seda, un paso de progresión real. |

Las formas exactas (3x3 con el bloque central rodeado, 2x2 para la Regadera) están en el código; los valores son ajustables sin tocar ninguna otra clase.

### 9.2. Comando administrativo (`/rsgive`)

Como respaldo/atajo para pruebas (u operadores que quieran saltarse el crafteo), `/rsgive <fridge|freezer|wateringcan|sprinkler> [jugador]` (permiso `realisticsurvival.give`, default `op`) entrega los ítems directamente — ver `commands/RSGiveCommand.java`. Tanto este comando como `recipes/RecipeManager` son añadidos fuera de la lista de paquetes de la sección 6 (no rompen la arquitectura original, solo la extienden) y ninguno contiene lógica de creación de ítems: ambos delegan en `ApplianceManager#createApplianceItem` y `WateringManager#createWateringCan/createSprinklerItem`.

## 🎨 10. Compatibilidad visual sin Resource Pack

Todo ítem/entidad `ItemDisplay` custom (Refrigerador, Congelador, Aspersor, Regadera) usa como base un **Material vanilla temáticamente razonable** en vez de un lienzo neutro (`PAPER`/`STICK`), para que el mecanismo se vea sensato incluso sin el Resource Pack cargado:

| Tipo | Material base (fallback) | CustomModelData (placeholder) | Orientación |
|---|---|---|---|
| Refrigerador | `DROPPER` | 1100001 | Mira al jugador que lo coloca, restringido a los 4 puntos cardinales |
| Congelador | `DISPENSER` | 1100002 | Mira al jugador que lo coloca, restringido a los 4 puntos cardinales |
| Regadera Manual | `GLASS_BOTTLE` | 1200001 | — (ítem de mano, no se coloca) |
| Aspersor | `DISPENSER` | 1200002 | Siempre mira hacia arriba, sin importar cómo se coloque |

Cuando el Resource Pack está presente, el `CustomModelData` de cada tipo debe mapear al modelo 3D real vía overrides de modelo de ítem (`item/<material>.json` en 1.21.4+); sin el Resource Pack, el cliente simplemente muestra el Material base tal cual — el mecanismo (hitbox, GUI, catch-up) es idéntico en ambos casos. Los valores de `CustomModelData` son placeholder y deben ajustarse cuando exista el Resource Pack real.

Refrigerador y Congelador usan Dropper/Dispensador (en vez de un mismo bloque) para que sean **visualmente distinguibles entre sí** incluso sin Resource Pack. La orientación cardinal se logra rotando la entidad `ItemDisplay` (no el bloque `BARRIER`, que no tiene estado de orientación) mediante `Transformation`/`AxisAngle4f` sobre el eje Y — el ángulo exacto por punto cardinal (`ApplianceManager#CARDINAL_Y_DEGREES`) y la inclinación del Aspersor (`WateringManager#FACING_UP_TRANSFORMATION`) son **valores best-effort**: no hay forma de verificar la orientación visual real sin un cliente de Minecraft corriendo, así que quedan documentados en el código como el primer punto a ajustar (girar en incrementos de 90°) al probar en el servidor.
