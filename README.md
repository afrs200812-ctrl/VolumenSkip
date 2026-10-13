# VolumenSkip — prototipo 0.1

Cambia de canción con los botones de volumen, en cualquier app de música.

| Acción (con música sonando) | Resultado |
|---|---|
| Mantener **Volumen +** ~0,6 s | ⏭ Siguiente canción (vibra) |
| Mantener **Volumen −** ~0,6 s | ⏮ Canción anterior (vibra) |
| Toque corto | Sube / baja volumen normal |

Si no hay música sonando, los botones funcionan como siempre.

## Cómo instalarla
1. Descomprime el ZIP y abre la carpeta `VolumenSkip` en **Android Studio**.
2. Espera a que sincronice Gradle (descarga lo necesario automáticamente).
3. Conecta tu celular con **Depuración USB** activada y pulsa ▶ **Run**.
   - O bien: *Build → Build APK(s)* y pasa el APK al teléfono.
4. Abre la app → **Activar en Accesibilidad** → busca *VolumenSkip* → actívalo.
5. Pon música en Spotify / YouTube Music / etc. y prueba manteniendo los botones.

## Cómo funciona
- `VolumeKeyService.kt`: servicio de accesibilidad que intercepta las teclas de volumen,
  mide cuánto tiempo se mantienen y envía las teclas multimedia estándar
  (`MEDIA_NEXT` / `MEDIA_PREVIOUS`) a la app que esté reproduciendo.
- `MainActivity.kt`: pantalla con el estado del servicio y botones de prueba.

## Limitaciones conocidas del prototipo
- **Con la pantalla apagada** Android no entrega las teclas de volumen a los servicios
  de accesibilidad en la mayoría de teléfonos. Funciona con pantalla encendida y,
  en muchos modelos, también en la pantalla de bloqueo. Lograrlo con pantalla apagada
  requiere otra técnica (MediaSession + ventana superpuesta o root) → siguiente versión.
- Algunas marcas (Xiaomi, Huawei, Samsung) pueden cerrar el servicio en segundo plano:
  desactiva la optimización de batería para VolumenSkip.
- El tiempo de pulsación larga se cambia en `LONG_PRESS_MS`.
