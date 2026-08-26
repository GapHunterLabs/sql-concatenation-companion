# Cómo probar este plugin

Este plugin avisa cuando el código arma una consulta a una base de
datos "pegando" texto de forma insegura (una práctica que puede
permitir que alguien manipule la base de datos con datos maliciosos
— se llama "inyección SQL").

## Qué hacer

1. En el panel de la izquierda, abrí el archivo
   **`OrderRepository.java`** (dentro de `src` → `main` → `java` →
   `com` → `acmecorp` → `orders`).
2. Mirá los 3 métodos (bloques de código), uno por uno.

## Qué deberías ver

- En el primer bloque (`findVulnerable`): **debería aparecer un
  aviso** en la línea que arma la consulta pegando el texto con
  `+ userId` — porque eso es justamente el problema que detecta.
- En el segundo bloque (`buildBaseQuery`): **no debería aparecer
  ningún aviso** — ahí solo se está pegando texto fijo, sin ningún
  dato que venga de otro lado, así que no hay riesgo real.
- En el tercer bloque (`findSafe`): **no debería aparecer ningún
  aviso** — ese código ya usa la forma segura de hacer consultas.

## Si algo no se ve así

Sacá la captura igual, y avisame qué bloque no coincide con lo de
arriba.
