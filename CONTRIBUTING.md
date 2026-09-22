# Contribuir a KarinFLiX

¡Gracias por tu interés en contribuir! Este documento te guía paso a paso.

## 🚀 Cómo contribuir

### 1. Prerrequisitos

- **Android**: JDK 11+, Android Studio, Android SDK (compileSdk 36)
- **Backend Python**: Python 3.10+, pip
- **Git**: Configurado con tu usuario

### 2. Fork y clone

```bash
git fork https://github.com/manuel00084/KarinFliX.git
git clone https://github.com/TU_USUARIO/KarinFliX.git
cd KarinFliX
git remote add upstream https://github.com/manuel00084/KarinFliX.git
```

### 3. Rama de features

```bash
git checkout -b feature/nombre-feature
```

Sigue estas convenciones de nombres:
- `feature/nombre` — nueva funcionalidad
- `fix/descripcion` — corrección de bug
- `docs/descripcion` — documentación
- `refactor/modulo` — refactorización
- `test/modulo` — tests

### 4. Código y estilo

#### Kotlin (Android)
- Usa **kotlin-style** con 4 espacios de indentación
- Nombres de funciones en `camelCase`
- Clases y objetos en `PascalCase`
- Documenta funciones públicas con KDoc

```kotlin
/**
 * Obtiene el estado actual de la reproducción.
 * @return El estado actual del reproductor.
 */
fun getPlaybackState(): PlaybackState = ...
```

#### Python (Backend)
- PEP 8, 4 espacios de indentación
- Type hints obligatorios
- Docstrings en formato Google Style

```python
def get_device_status(uuid: str) -> DeviceStatus:
    """Retorna el estado de un dispositivo.

    Args:
        uuid: UUID del dispositivo.

    Returns:
        DeviceStatus del dispositivo.
    """
```

### 5. Tests

#### Android
```bash
# Tests unitarios
./gradlew test

# Tests de instrumentación
./gradlew connectedDebugAndroidTest
```

#### Python
```bash
# Ejecutar todos los tests
pip install -r karin_link/requirements.txt
pip install pytest pytest-asyncio httpx
cd karin_link
pytest tests/ -v

# Cobertura
pytest tests/ --cov=karin_link --cov-report=html
```

### 6. Commit y PR

```bash
git add .
git commit -m "feat: agregar búsqueda difusa en el scraper"
git push origin feature/nombre-feature
```

Luego abre un **Pull Request** contra `main` con:
- Título descriptivo
- Descripción del cambio
- Screenshots si aplica (UI)
- Resultados de tests
- Relación con issues existentes (`Closes #12`)

### 7. Código de conducta

- Sé respetuoso y constructivo
- No discriminate ni acoses
- Revisa código de otros con mente abierta
- Reporta problemas con detalles y pasos reproductibles

## 📋 Checklist antes de un PR

- [ ] Código compila sin errores
- [ ] Todos los tests pasan
- [ ] Añadiste tests para nueva funcionalidad
- [ ] Documentación actualizada
- [ ] CHANGELOG.md actualizado
- [ ] Sin warnings de lint
- [ ] Variables de secret están en `.env`, no en código

## 🐛 Reportar bugs

Usa el template de issue:
```
### Descripción
### Pasos para reproducir
### Comportamiento esperado
### Comportamiento actual
### Dispositivo/OS
### Logs
### Screenshots (opcional)
```

## 📖 Documentación

- Edita `README.md` para features principales
- Usa `docs/` para análisis técnicos profundos
- Actualiza `CHANGELOG.md` con cada release

## 🤝 Proceso de revisión

1. Un maintainer asignará un revisor
2. El revisor revisará código, tests y documentación
3. Se pedirán cambios si es necesario
4. Al aprobarse, se mergeará con `squash merge`

---

Gracias por contribuir a KarinFLiX! 🎬
