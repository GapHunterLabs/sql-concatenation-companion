# Cómo probar este plugin

Este plugin avisa cuando el código arma una consulta a una base de
datos "pegando" texto de forma insegura (una práctica que puede
permitir que alguien manipule la base de datos con datos maliciosos
— se llama "inyección SQL").

## Qué hacer

1. En el panel de la izquierda, abrí el archivo
   **`OrderRepository.java`** (dentro de `src` → `main` → `java` →
   `com` → `acmecorp` → `orders`).
2. Mirá los métodos (bloques de código), uno por uno.
3. Después abrí **`scripts/report.py`**.

## Qué deberías ver en `OrderRepository.java`

- `findVulnerable`: **debería aparecer un aviso** en la línea que arma
  la consulta pegando `+ userId`.
- `findByStatus`: **debería aparecer un aviso** que nombra `status`
  (no las constantes `TABLE_ORDERS` ni `KEY_STATUS` que están antes).
- `findByStatusSafe`: **ningún aviso** — solo pega constantes y usa `?`
  para el valor.
- `findById`: **ningún aviso** — `orderId` es un número (`int`), no
  puede traer texto malicioso.
- `buildBaseQuery`: **ningún aviso** — solo pega texto fijo.
- `findSafe`: **ningún aviso** — ya usa la forma segura.

## Qué deberías ver en `scripts/report.py`

- `orders_for`: **debería aparecer un aviso** (f-string con `customer`).
- `order_count`: **ningún aviso** — `%d` solo acepta números.
- `orders_safe`: **ningún aviso** — consulta parametrizada.

## Si algo no se ve así

Sacá la captura igual, y avisame qué bloque no coincide con lo de
arriba.
