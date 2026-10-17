# Pipeline Natural Premium

Todos os parâmetros vivem em `imaging/.../pipeline/ProcessingProfile.kt`. Novos perfis são
novas instâncias desse objeto, não novos caminhos de código.

## 1. Fluxos de captura e quando cada um é usado

| Fluxo | Origem | Prós | Contras | Uso no app |
|---|---|---|---|---|
| **JPEG do sistema** | ISP + HAL (NR, nitidez, curva da Samsung para apps de terceiros) | Rápido, já "pronto", pouco ruído | Nitidez e curva do fabricante embutidas; 8 bits; sem multiquadro de apps de terceiros | Sempre capturado como **original** (referência de comparação) |
| **YUV com curva sRGB, nitidez desligada** | ISP com `TONEMAP_PRESET_CURVE_SRGB`, `EDGE_MODE_OFF`, NR mínimo | Transferência conhecida (linearizável), sem halos do HAL, ideal para fusão | Mais ruído por quadro; depende de `MANUAL_SENSOR` | **Burst** do Natural Premium |
| **YUV padrão** | Mesmo processamento do JPEG | Disponível em quase tudo | Curva/nitidez do HAL | Original quando não há fluxo JPEG; fallback |
| **RAW/DNG** | Sensor, antes do ISP | Máxima latitude | Exige demosaico, NR, cor e tom próprios; pesado | Opcional, salvo à parte (não processado ainda) |
| **Alta resolução** | Modo de pixel máximo | Detalhe em boa luz | Sem burst, mais ruído, arquivos enormes | Opção; processado se ≤ 52 MP |
| **Ultra HDR** | Nosso mapa de ganho sobre o SDR | Altas luzes brilham em telas HDR, SDR idêntico | Requer Android 14+ e visualizador compatível | Opção |

Não se presume que RAW seja melhor: o ISP faz demosaico, correção de lente e NR de alta
qualidade em hardware. O burst YUV aproveita esse trabalho e substitui só o que importa para a
estética (tom, cor, ruído residual e nitidez).

### HDR: quatro coisas diferentes
1. **HDR na captura** — subexposição planejada para não estourar o céu (até −2,3 EV conforme
   o recorte medido no fluxo de análise).
2. **Fusão de múltiplos quadros** — recupera o ruído das sombras que a subexposição custou
   (√N em áreas estáticas).
3. **Mapeamento tonal** — comprime a faixa de iluminação de grande escala mantendo o contraste
   local, e uma curva global com ombro suave.
4. **Apresentação HDR** — Ultra HDR (gain map) para telas que mostram acima do branco SDR.

## 2. Etapas

1. **Captura** — `CapturePlanner`: exposição por quadro limitada a ~1/(2·focal eq.) × OIS,
   ISO compensado, N = 4…15 quadros por ISO/modo/tempo total, AWB e AF travados.
2. **Fusão** (`BurstMerger`) — referência = mais nítida entre as 3 primeiras (energia de
   gradiente); alinhamento em pirâmide de luma (/64, /16, /4 com ±4 px; refinamento em
   resolução total ±1 px) por tiles de 32 px; fusão por blocos 2×2 com peso de robustez
   calculado contra o ruído esperado — **luma e croma** (um objeto com a mesma luma do fundo
   não deixa fantasma colorido). Quadros muito borrados são descartados.
3. **Análise de cena** (`SceneAnalyzer`, ~640 px) — WB, base de iluminação (guided filter no
   log da luminância), percentis, chave com peso no centro e nos rostos, EV da cena pelos
   metadados (dia/interior/noite como fatores contínuos, sem troca brusca de estilo).
4. **Balanço de branco** (`WhiteBalance`) — CCT do branco que o AWB neutralizou, estimado pelos
   ganhos do AWB e pelas matrizes de calibração do sensor (iteração estilo DNG). Retém 26% do
   calor em luz de tungstênio (2850 K → renderizado a ~4900 K) e 10% do frio em sombra.
   Corrige 50% de dominante verde/magenta medida em neutros. Fotos seguidas com a mesma lente
   em 20 s convergem para o mesmo branco.
5. **Tom / HDR local** — compressão separada de altas luzes (até 2,4 stops acima da chave) e
   sombras (limitada a 0,78 para manter profundidade; desligada à noite). O deslocamento tonal
   é aplicado em resolução total por *guided upsampling* (Δ ≈ a·log Y + b), que segue bordas
   e evita halos. Curva global: contraste 1,06 em torno do cinza 18%, ombro exponencial,
   branco a +3,2 stops, leve *flare* subtraído. Para fontes já renderizadas pelo sistema a
   curva é identidade e a exposição só recebe ajuste de ±0,3 EV.
6. **Cor** (`ColorLook`, Oklab) — croma ×1,03 + *vibrance* 0,08 (mais em cores apagadas),
   janelas de matiz suaves: vermelhos ×0,96, verdes ×0,95 e −3° (folhagem menos neon), azul do
   céu ×1,04 e levemente mais profundo; dessaturação só de altas luzes quase brancas.
7. **Pele** — probabilidade por matiz/croma/luminosidade × prior de rosto. Só corrige
   exageros: croma acima de 0,115 comprimido, matiz fora de ±12° do centro puxado 25% do
   excesso. **Nunca clareia, nunca adiciona croma**, nunca suaviza.
8. **Modelo de ruído** — medido na própria imagem já renderizada (patches 12×12,
   segunda diferença, cauda inferior por faixa de luminosidade), descartando patches
   anisotrópicos (cabelo, pelos, tecido). Assim a mesma etapa é leve num JPEG limpo e forte
   num quadro ruidoso.
9. **Redução de ruído** — luma: guided filter com regularização = (2σ)² do modelo; 30% do
   ruído removido volta como grão fino (anti-plástico). Croma: filtro guiado pela luma em meia
   resolução; diferenças maiores que o ruído são preservadas. Regiões onde a fusão rejeitou
   quadros recebem mais NR. Fonte do sistema: luma ×0,35, croma ×0,6.
10. **Nitidez** — USM de raio ~1 px na luma denoised; *coring* suave proporcional ao ruído
    residual; quantidade por fonte (burst 0,6; quadro sRGB 0,35; sistema 0,1) × lente × 0,6
    em pele × menos nas sombras; **limite de overshoot** contra o mínimo/máximo 3×3 (±1,2%),
    o que elimina halos claros e escuros. Microcontraste de banda média pequeno (0,04 +
    compensação da compressão HDR), só em tons médios.
11. **Saída** — Oklab → linear → (Display P3) → mapeamento de gamut híbrido (metade
    dessaturação para o cinza de mesma luminosidade, metade escurecimento — mantém o azul do
    céu) → sRGB OETF → dither → JPEG. Ultra HDR: mapa de ganho em 1/4 de resolução com até
    2 stops.
12. **Metadados** — EXIF (fabricante, modelo, data, exposição, ISO, f, focal, focal eq.).
    Orientação gravada nos pixels do processado; no original, via tag EXIF (sem recompressão).

## 3. Consistência entre lentes

- Na câmera lógica, a troca de lente e o casamento de cor são feitos pelo HAL.
- Cada captura usa a **calibração da lente física ativa** para estimar o branco.
- `LensProfile` aplica priors por classe de focal (ultra-wide e tele: nitidez ×0,9, NR ×1,1–1,15)
  e aceita uma matriz de cor residual por lente — a ser medida com o protocolo de comparação
  (uma carta de cores fotografada com cada lente).

## 4. Pontos de ajuste que dependem do aparelho real

- Os alvos de exposição e o contraste (comparar com iPhone na mesma cena).
- Política de NR do HAL por ISO nos quadros do burst (`CapturePlanner`).
- Matriz residual por lente.
- Se o S26 Ultra não oferecer `TONEMAP_PRESET_CURVE`/`CONTRAST_CURVE` ou `MANUAL_SENSOR` a
  apps de terceiros, o burst cai para AE travado + curva do HAL e a fonte é tratada como
  "renderizada pelo sistema" (efeito mais leve).

## 5. Revisão 0.2.0 — auditoria de nitidez, pele e captura nativa

**Diagnóstico** (antes desta versão):
- Fonte processada pelo sistema (ISP com `EDGE_MODE_HIGH_QUALITY`) recebia mais uma máscara de
  nitidez (0,10) — **nitidez dupla**.
- O burst próprio (ISP sem nitidez) recebia USM 0,60 + microcontraste até 0,12 e permitia
  overshoot de 1,2% — contornos de poros, cílios e sobrancelhas marcados demais.
- Raio fixo, sem proteção de áreas lisas nem queda com o ruído.
- O burst próprio desligava NR, nitidez e curva do ISP sem validação no aparelho.

**Agora:**
- Fonte do sistema: **nenhuma** nitidez adicional; NR de luma só residual (×0,2) com 50% de grão.
- Nitidez própria: burst 0,38 / quadro sRGB 0,24 (×0,7 Suave, ×1,25 Definida); raio σ 0,9 px
  escalado pela resolução; limiar absoluto + limiar proporcional ao ruído; **confiança de
  textura** do filtro guiado (áreas lisas recebem 35%); quantidade cai pela metade quando o
  ruído atinge σ 0,012; pele ×0,45; overshoot máximo **0,6%**; microcontraste 0,025.
- Pele: NR cromático ×1,6 apenas na máscara de pele (manchas/vermelhidão de cor, sem tocar a
  textura de luma); *roll-off* de brilho especular calculado sobre o **excesso local** (uma pele
  clara uniforme não é escurecida); suavização sutil **opcional, desligada por padrão**, atuando
  só na banda média com proteção de contornos.
- NR de luma ×0,7 em boa luz.
- Captura padrão **nativa**: cada quadro do burst mantém NR/nitidez/curva HQ do ISP, AE travado,
  sem subexposição; a fusão só remove ruído residual. O burst com ISP desligado virou opção
  "Multiquadro próprio (experimental)" em Ajustes.
- Temperatura: com status térmico ≥ SEVERE o burst cai para 3 quadros (a resolução nunca é reduzida).

**Medição com as mesmas entradas reais** (fonte do sistema, versão anterior → nova):

| Foto | Energia de gradiente entrada / anterior / nova | Overshoot anterior → nova |
|---|---|---|
| babuíno (pelos) | 14,51 / 14,55 / 14,17 | 0,1455 → 0,1408 |
| prédio (linhas) | 7,73 / 7,69 / 7,50 | 0,1093 → 0,1044 |
| retrato no estádio | 5,98 / 5,97 / 5,79 | 0,1224 → 0,1165 |
| frutas (cores) | 4,67 / 4,95 / 4,75 | 0,1129 → 0,1074 |

Menos halos em todas; detalhe dentro de 0–3% da entrada (parte da diferença vem do ajuste
tonal/cor, não da NR). São JPEGs públicos, não capturas do S26 Ultra: a validação de pele,
cílios e sobrancelhas em retratos reais ainda precisa ser feita no aparelho.

## 6. Revisão 0.3.0 — foto noturna real e fonte Híbrida

**Análise da foto de teste (interior, fim de tarde, S26 Ultra):** o EXIF trazia
`Software: CameraPremium - Original (sistema)` — era o JPEG que o Android entrega a apps de
terceiros (quadro único, ISO 167, 1/60 s, f/1.4, 4000×2252), **não** a versão Natural Premium.
Defeitos visíveis: textura de "aquarela"/vermes na malha da camisa e no fundo (NR espacial HQ),
contornos brancos nas dobras e pele cerosa com bordas marcadas (nitidez HQ). A câmera nativa da
Samsung usa um pipeline multiquadro proprietário que **não** é exposto ao Camera2 comum.

**Mudanças:**
- **Fonte Híbrida (nova padrão):** mantém curva de tom e cor do ISP, mas pede NR `MINIMAL`
  (ou `FAST`) e nitidez `OFF` em 5–14 quadros (AE travado); o ruído sai pela fusão temporal.
- **Ruído temporal:** a fusão mede o ruído real pela diferença entre quadros alinhados (a
  textura é igual em todos e se cancela) e esse valor limita o modelo de ruído espacial — malha,
  cabelo e barba não são mais confundidos com ruído.
- **Camera2 Extensions:** modo Noite usa a extensão Noite do fabricante quando exposta (padrão
  ligado); modo Foto pode usar a extensão Auto (opcional). Natural Premium aplica toque leve por
  cima. Se a extensão falhar, volta sozinho à sessão normal. O diagnóstico lista as extensões,
  tamanhos e chaves aceitas.

**Teste com verdade conhecida** (malha mesclada, ruído σ 7, `teste_tecido_verdade_isp_hibrido.jpg`):

| | erro L\* vs verdade | energia de textura (real 3,24) | overshoot |
|---|---|---|---|
| quadro único com NR+nitidez fortes (modelo do ISP) | 2,95 | 3,32 (inclui ruído/halos) | 0,084 |
| Híbrido (7 quadros) | **1,85** | **3,38** | **0,075** |

O erro residual do Híbrido (~1,8) é quase todo um ajuste de brilho de +0,1 EV, não perda de detalhe.

**Sobre "XMLs":** configurações XML de GCam (Google Camera modificada) e presets XMP/DCP do
Lightroom são *parâmetros* para pipelines de terceiros (as bibliotecas fechadas do HDR+ do
Google, o motor do Adobe Camera Raw). Não contêm algoritmos e não podem ser carregados neste app.
O equivalente aqui é o `ProcessingProfile` — todos os parâmetros num único lugar, ajustáveis por
medição (veja o protocolo de comparação com iPhone).

## 7. Revisão 0.3.1 — análise de 12 fotos reais (Original × Natural Premium)

Todas as 12 fotos enviadas são do app no SM-S948B (EXIF `Software`): 6 "Original (sistema)" (o
JPEG do HAL, com EXIF completo da Samsung) e 6 "Natural Premium" (fonte Híbrida). Cinco cenas
formam pares (cabelo/tatuagem, relógio, rosto, espetinho, cozinheiro); fotos de dia, ISO 20–120.

Medições (cortes 100%, luminância sRGB):

| cena | energia de detalhe (Laplaciano) Original → NP | p1 / p99 Original → NP |
|---|---|---|
| cabelo | 5,8 → 3,0 | 10 / 234 → 13 / 209 |
| relógio | 14,3 → 6,3 | 9 / 186 → 11 / 204 |
| rosto | 3,0 → 3,1 | 29 / 222 → 28 / 217 |
| espetinho | 8,6 → 3,9 | 41 / 250 → 63 / 237 |
| cozinheiro | 5,8 → 2,4 | 31 / 247 → 32 / 233 |

**Diagnóstico** (a partir dos cortes, não de suposições):
1. **Cor falsa "arco-íris"** na grelha, corrente, toalha, letras do relógio e barba no NP. É o
   artefato de demosaico que o ISP normalmente limpa; com NR `MINIMAL` ele chega ao app, e a NR de
   croma *preservava* desvios grandes de cor (tratados como borda real).
2. **Perda de textura fina** (pele da mão, pescoço). As bordas fortes (letras do relógio) estão
   nítidas, então **não** é desfoque da fusão: é NR de luma. A estimativa temporal de ruído usava
   a mediana das diferenças entre quadros; em close-ups quase todo bloco é textura, e o
   desalinhamento subpixel da mão soma diferença → ruído superestimado → NR forte.
3. **Véu cinza**: o fluxo YUV entregue a apps de terceiros é mais plano que o JPEG da Samsung
   (pretos levantados no espetinho, brancos baixos no cabelo/cozinheiro). Além disso, o ajuste de
   exposição podia *escurecer* cenas claras em até 0,3 EV.

Observação honesta: o Original do HAL tem nitidez exagerada ("crocante", com halos); a meta não é
igualar a energia de detalhe dele, e sim recuperar a textura real sem artefatos.

**Mudanças:**
- **Supressão de cor falsa** (`falseColorSuppression` 0,9): em pixels de textura fina (confiança
  do filtro de luma) e superfícies quase neutras, o resíduo de croma em resolução cheia é trocado
  pela croma guiada pela luma em meia resolução (a resolução 4:2:0 de qualquer JPEG). Detalhe
  colorido saturado (tecido listrado) mantém a croma.
- **Ruído temporal pelo quartil inferior** (interpolado no histograma) em vez da mediana:
  textura/desalinhamento só aumentam diferenças, então o quartil inferior quase não é afetado.
- **Níveis da fonte Híbrida**: ponto preto puxado (50% do excesso, máx. 0,02 linear) e ponto
  branco esticado até p99,5 ≈ 0,82 linear (ganho máx. 1,12, joelho suave acima de 0,8 — nunca
  clip). Cenas noturnas mantêm o escuro; com >1% de pixels estourados não há ganho.
- Ajuste de exposição sobre fonte já exposta pelo ISP: no máximo −0,1 EV (antes −0,3).
- Nitidez Híbrida 0,32 → 0,40 (o quadro chega com nitidez do ISP desligada).

**Testes novos:** cor falsa (desvio de croma 0,0368 → 0,0021, limpo 0,0010; textura de luma
85%; listras coloridas de 4 px mantêm 99% da croma) e véu (L\* p1 30,4 → 26,2; p99 76,0 → 77,7;
0% estourado; cena noturna quase inalterada). No teste de tecido com verdade conhecida o erro do
Híbrido hoje é 2,24 (ISP 2,95) — o valor 1,85 citado na seção 6 não se reproduz com o código
atual nem com o estimador antigo (2,26).

**Não verificado:** o efeito real no aparelho. Repita os mesmos pares de cena com a v0.3.1.
