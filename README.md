# CameraPremium

Aplicativo de câmera Android nativo (Kotlin + Camera2) para o **Galaxy S26 Ultra**, com um
pipeline fotográfico próprio — **Natural Premium** — que busca uma estética próxima à de um
iPhone recente: cores naturais, pele agradável, HDR equilibrado, nitidez contida e ruído
reduzido sem aparência plástica. Todo o processamento acontece no aparelho.

> Não reproduz algoritmos proprietários da Apple nem da Samsung. Usa técnicas públicas de
> fotografia computacional (fusão multiquadro robusta, mapeamento tonal local guiado, cor em
> Oklab, ruído medido na própria imagem) e um protocolo de comparação para ajuste contínuo.

**Estado atual:** compila e gera APK assinado; pipeline validado em JVM (41 verificações
objetivas). **Nada foi testado ainda em um aparelho físico** — veja
[docs/TESTES.md](docs/TESTES.md) para o que precisa ser validado no S26 Ultra.

## O que tem

| Área | Implementado |
|---|---|
| Câmeras | Inventário dinâmico de câmeras lógicas e físicas; fatores de zoom derivados das distâncias focais reais (0,6×/1×/2×/3×/5×/10× quando existirem); troca suave com quadro congelado; frontal com espelhamento opcional |
| Captura | Burst planejado (subexposição para proteger altas luzes, exposição curta anti-tremor, N quadros conforme o ISO), curva sRGB e nitidez do HAL desligada nos quadros do burst, original do sistema sempre capturado junto |
| Processamento | Fusão multiquadro sem fantasmas → HDR local → curva tonal → balanço de branco com ambiência → cor/pele em Oklab → redução de ruído adaptativa → nitidez com controle de halos → sRGB ou Display P3 → Ultra HDR opcional |
| Comparação | Original preservado; tela com divisória arrastável, segurar para alternar, zoom sincronizado; escolha de qual versão fica na galeria |
| Modos | Foto, Noite (burst longo), Pro (ISO, obturador, foco, WB), Retrato (somente se o aparelho expuser o bokeh público do Camera2) |
| Extras | Flash auto/ligado/desligado, flash de tela, temporizador 3/10 s, grade, proporções 4:3/16:9/1:1, bloqueio AE/AF, RAW/DNG, alta resolução (quando exposta), EXIF, botão de volume como disparador |
| Diagnóstico | Relatório exportável das capacidades reais do aparelho e dos tempos das capturas |

## Abrir e compilar

### Android Studio (recomendado na sua máquina)
1. Abra a pasta do projeto no Android Studio (Ladybug ou mais recente, JDK 17+).
2. Aguarde o sync do Gradle (AGP 8.13, Kotlin 2.2.21, compileSdk 35). O app não usa AndroidX.
3. Run ▶ no módulo `app`, ou na linha de comando:

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Build offline (sem acesso aos servidores do Google)
Usado neste ambiente, onde `dl.google.com`/`maven.google.com` estão bloqueados. Requer
`kotlinc`, `aapt2`, `dx`, `zipalign`, `apksigner` (pacotes `android-sdk-build-tools` do
Ubuntu) e um `android.jar` de API 35:

```bash
tools/build-offline.sh
# APK: build/outputs/CameraPremium-debug.apk
```

O script verifica que nenhum código chamado usa `invokedynamic` (o `dx` não faz desugaring).

## Instalar no Galaxy S26 Ultra

1. No aparelho: **Configurações → Sobre o telefone → Informações do software →** toque 7× em
   *Número de compilação*. Depois **Opções do desenvolvedor → Depuração USB**.
2. Conecte por USB e autorize o computador.
3. Instale:

```bash
adb devices                       # deve listar o aparelho como "device"
adb install -r build/outputs/CameraPremium-debug.apk     # ou app-debug.apk do Gradle
adb shell am start -n com.camerapremium/com.camerapremium.app.ui.CameraActivity
adb logcat -s CameraPremium       # logs de sessão, plano de burst e tempos
```

Para mandar o relatório de capacidades: **Ajustes → Diagnóstico da câmera → Exportar**
(fica em `Download/CameraPremium/`).

## Ferramentas de desenvolvimento do pipeline (desktop)

```bash
tools/imaging-tests.sh                       # suíte de testes fotográficos (ou ./gradlew :imaging-cli:imagingTests)
tools/imaging-tests.sh process in.jpg out.jpg --side-by-side lado_a_lado.jpg
tools/imaging-tests.sh burst out.jpg q1.jpg q2.jpg q3.jpg     # funde quadros reais
tools/imaging-tests.sh compare galaxy.jpg iphone.jpg --roiA 0.4,0.2,0.2,0.2 --roiB 0.42,0.2,0.2,0.2
tools/imaging-tests.sh synth cenas/          # cenas sintéticas de validação
```

## Estrutura

```
imaging/       pipeline fotográfico em Kotlin puro (sem Android): testável no PC
  burst/       alinhamento e fusão multiquadro
  pipeline/    análise de cena, tom/HDR, balanço de branco, cor/pele, ruído, nitidez, saída
  color/       sRGB, Display P3, Oklab, Bradford, CCT, CIEDE2000
  metrics/     métricas objetivas (comparação com iPhone, testes)
imaging-cli/   CLI de desktop + suíte de testes
app/           aplicativo Android (Camera2 + Views próprias)
  camera/      inventário de lentes, controlador Camera2, planejador de burst
  processing/  job de captura, processador em segundo plano, adaptadores Bitmap
  storage/     MediaStore e histórico de capturas
  ui/          telas e componentes desenhados (ícones próprios)
tools/         build offline, testes, verificação de bytecode
docs/          arquitetura, pipeline, testes, protocolo de comparação com iPhone
```

## Documentação

- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — decisões técnicas, threads, memória.
- [docs/PIPELINE.md](docs/PIPELINE.md) — fluxos de captura e cada etapa do processamento.
- [docs/TESTES.md](docs/TESTES.md) — relatório de testes, limitações e checklist no aparelho.
- [docs/PROTOCOLO_COMPARACAO_IPHONE.md](docs/PROTOCOLO_COMPARACAO_IPHONE.md) — como comparar e ajustar.
