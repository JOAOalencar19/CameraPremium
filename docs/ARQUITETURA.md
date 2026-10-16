# Arquitetura

## Análise do ambiente (sessão de desenvolvimento inicial)

| Item | Encontrado |
|---|---|
| Projeto existente | Apenas `README.md` — projeto criado do zero |
| JDK | OpenJDK 21.0.12 |
| Gradle | 8.14.3 (wrapper incluído) |
| Android SDK / Android Studio | Ausentes; `dl.google.com` e `maven.google.com` bloqueados pela política de rede |
| Maven Central | Disponível no início, depois limitado (HTTP 429, `retry-after` ~11 h) |
| Ferramentas obtidas | `kotlinc` 2.2.21 (GitHub/JetBrains), `aapt2`/`dx`/`zipalign`/`apksigner`/`adb` (build-tools 29 do Ubuntu), `android.jar` API 35 (robolectric android-all, Maven Central) |
| ADB / aparelho | `adb` instalado; **nenhum dispositivo** (ambiente em nuvem, sem USB) |

Consequências diretas:
- Não havia como baixar AGP, AndroidX, CameraX ou Compose. O app foi escrito **só com o
  framework Android**, o que compila aqui e também no Android Studio (com AGP).
- As capacidades da câmera do S26 Ultra **não puderam ser inspecionadas**. Por isso o app
  detecta tudo em tempo de execução e tem uma tela de diagnóstico exportável.

## Decisões técnicas

### Camera2 em vez de CameraX
O objetivo é qualidade, e os recursos que importam para isso só existem em Camera2:
controle por quadro em um burst (`captureBurst` com exposição manual idêntica em todos os
quadros), curva tonal `PRESET_CURVE sRGB` (dados com transferência conhecida para o nosso
mapeamento tonal), `EDGE_MODE_OFF` (evitar empilhar a nitidez do HAL com a nossa), leitura das
matrizes de calibração do sensor, identificação da lente física ativa
(`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`), RAW + `DngCreator`, modo de pixel máximo.
CameraX também não estava disponível neste ambiente.

### Views do framework em vez de Jetpack Compose
Compose não podia ser baixado. Em vez de um protótipo sem compilar, a UI foi feita com Views
do framework e componentes desenhados à mão (ícones vetoriais próprios em `Glyphs.kt`,
obturador, barra de zoom, visor). É leve, sem dependências e fácil de migrar: as telas só
dependem de `CameraController`/`PhotoProcessor` através de interfaces `Listener`.

### Pipeline em módulo Kotlin puro (`:imaging`)
Nenhuma classe Android no pipeline. Vantagens: testes objetivos no PC, CLI para processar e
comparar fotos reais (inclusive do iPhone), e o mesmo código roda no app. O app só fornece
adaptadores (`BitmapSource`, `OrientedBitmapSink`, cópia de `Image` → `YuvFrame`).

### Sem IA pesada
Nenhum modelo de ML. Rostos vêm da detecção do próprio HAL (`STATISTICS_FACES`), usada só como
prior suave para exposição e pele. Um modelo só deve entrar se provar ganho real.

## Módulos e fluxo

```
CameraActivity ──> CameraController (thread "camera")
   │                 ├─ sessão: preview + análise (VGA) + YUV máx + JPEG máx (+RAW)
   │                 ├─ 3A: AF/AE por toque, bloqueio, precaptura de flash
   │                 └─ captura: [original do sistema] + [burst N quadros YUV]
   │                          │ thread "camera-images": copia Image → YuvFrame
   │                          ▼
   │              PhotoProcessor
   │                 ├─ executor "merge":    BurstMerger.add() em streaming
   │                 └─ executor "pipeline": PhotoPipeline → JPEG/Ultra HDR → EXIF → MediaStore
   ▼
ReviewActivity  (original × processado, escolha da versão)
```

### Threads
- **main**: UI apenas.
- **camera**: todo o estado do `CameraController` (nada de locks na UI).
- **camera-images**: callbacks dos `ImageReader`; cópia imediata e `close()` para não travar o HAL.
- **merge** (1 thread) e **pipeline** (1 thread), mais um `WorkerPool` de até 8 threads usado
  por dentro da fusão e do pipeline. A próxima foto pode ser fundida enquanto a anterior renderiza.

### Memória (12 MP)
- Fusão em streaming: só referência + acumuladores (~100 MB) + o quadro que chega (18 MB).
  Um burst noturno de 12 quadros **não** guarda 12 quadros.
- Pipeline em tiles de 288×288 com halo de 20 px: ~7 MB de rascunho por thread; nenhuma cópia
  float da imagem inteira.
- Saída: o *sink* grava cada tile já rotacionado/espelhado no `Bitmap` final (sem cópia extra
  para rotacionar).
- Controle de fila: nova captura só quando não há outra fusão em andamento e no máximo uma
  foto aguardando pipeline. `android:largeHeap="true"`.
- Alta resolução: processada só até 52 MP (duas cópias ARGB); acima disso, mantém o JPEG do sistema.

### Sessões de câmera (cadeia de fallback)
Tentadas em ordem, verificando antes com `isSessionConfigurationSupported` quando disponível:
1. alta resolução (modo de pixel máximo), se ativado e suportado;
2. preview + análise + YUV + JPEG (+ RAW);
3. preview + análise + YUV (+ RAW) — o original vem de um quadro YUV com processamento padrão;
4. preview + YUV;
5. preview + JPEG (somente processamento do sistema).

### Zoom e lentes
`CameraInventory` lê todas as câmeras, inclusive as físicas escondidas atrás da lógica, calcula
a focal equivalente a 35 mm (`focal × 43,27 / diagonal do array ativo`) e gera as paradas de
zoom: a menor razão da câmera lógica (ultra-wide), 1×, 2×, cada tele física arredondada (ex.:
3×, 5×) e 10×. Cada parada sabe como ser alcançada:
- **câmera lógica** com `CONTROL_ZOOM_RATIO` (o HAL troca a lente física e faz o casamento de
  cor/exposição da fabricante) — padrão;
- **câmera física independente** quando o Android a expõe separadamente e o usuário escolhe
  "Lente física preferencial";
- recorte digital apenas onde não há lente nativa (ex.: 2× e 10×), indicado na UI.

A lente ativa reportada pelo HAL aparece como etiqueta ("Tele 5×") sobre a barra de zoom.
