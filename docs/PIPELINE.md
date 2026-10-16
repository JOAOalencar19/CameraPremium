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
