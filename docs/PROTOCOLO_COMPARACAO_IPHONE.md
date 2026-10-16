# Protocolo de comparação com iPhone

Objetivo: aproximar o perfil Natural Premium da estética de um iPhone recente com evidência,
não com impressão. Nenhuma afirmação de equivalência deve ser feita sem este registro.

## Preparação
- iPhone com câmera padrão, formato **Mais compatível (JPEG)** ou exporte HEIC → JPEG sem
  edição. Desligue estilos fotográficos (padrão) e ajustes manuais.
- Galaxy S26 Ultra com CameraPremium; a cada foto o app guarda **original do sistema** e
  **Natural Premium** — compare os três.
- Tripé ou apoio quando possível; mesma posição, distância e altura; fotos com até 30 s de
  diferença; mesma focal equivalente (1× com 1×, 5× com 5×…).
- Uma carta de cores (ColorChecker ou similar) e um cartão cinza ajudam muito nas cenas
  controladas.

## Cenas (mínimo 3 fotos de cada)
1. Retrato em luz natural (sombra aberta e sol)
2. Retrato em interior (luz quente e luz mista)
3. Tons de pele variados (pessoas diferentes, mesma luz)
4. Alto contraste / contraluz (janela, céu atrás da pessoa)
5. Céu e paisagem
6. Vegetação
7. Iluminação quente (tungstênio, velas, restaurante)
8. Noite urbana e interior escuro
9. Objetos e arquitetura (linhas, texturas finas)

## Medição
Copie as fotos para o computador e use a CLI:

```bash
# imagem inteira
tools/imaging-tests.sh compare galaxy.jpg iphone.jpg
# mesma região nas duas (x,y,w,h normalizados 0..1): rosto, céu, cartão cinza, folhagem
tools/imaging-tests.sh compare galaxy.jpg iphone.jpg --roiA 0.41,0.22,0.16,0.18 --roiB 0.44,0.20,0.16,0.18
```

O relatório traz: L\*a\*b\* médio, percentis de L\* (sombras/médios/altas luzes), branco
estourado, sombras esmagadas, croma médio e por faixa de matiz, dominante em neutros, energia
de gradiente (nitidez), textura média, ruído em áreas planas, índice de overshoot (halos) e
ΔE2000 entre as médias das regiões.

Como as fotos não são alinhadas pixel a pixel, compare **distribuições e regiões**, não pixels.

## Avaliação
Planilha por cena, para *original do sistema*, *Natural Premium* e *iPhone*:

| Critério | Medida | Visual (1–5) |
|---|---|---|
| Pele | ΔE2000 da região do rosto vs iPhone; matiz; L\* | naturalidade, textura |
| Exposição | L\* p50; L\* do rosto | |
| Altas luzes | branco estourado %, L\* p99 | detalhe no céu |
| Sombras | L\* p5, sombras esmagadas % | profundidade |
| Cor | croma por matiz; dominante em neutros | |
| Textura / nitidez | energia de gradiente, textura, overshoot | halos, "crocância" |
| Ruído | ruído L\* em área plana | plasticidade |
| HDR | — | halos, aparência artificial |

Regras de interpretação:
- **Mais nitidez, saturação ou contraste não significam imagem melhor.** Um overshoot maior
  que o do iPhone é defeito, mesmo que a imagem pareça "mais nítida".
- Separe captura de processamento: se o *original do sistema* já perde a cena (foco, tremor,
  estouro), o problema é de captura (plano do burst), não do pipeline.
- Ajuste **um parâmetro por vez** em `ProcessingProfile` e reprocesse as mesmas fotos com
  `tools/imaging-tests.sh process` (as fotos do app: o original está em
  `files/originals` do app; para reprocessar o burst é preciso salvar os quadros — próximo passo).
- Registre a versão do perfil e os números antes/depois de cada ajuste.

## Ajustes típicos e onde ficam
| Observação | Parâmetro |
|---|---|
| Natural Premium mais escuro/claro que o iPhone em geral | `targetKeyDay`, `targetKeyIndoor`, `targetKeyNight` |
| Céu mais estourado | `highlightHeadroomStops` ↓, `minHighlightCompression` ↓ |
| Sombras cinzentas / HDR demais | `minShadowCompression` ↑, `contrast` ↑ |
| Pele alaranjada | `skinChromaKnee` ↓, `skinHuePull` ↑ |
| Interior quente demais / frio demais | `warmRetention` |
| Verde neon | `greenChroma` ↓, `greenHueShiftDeg` |
| Plástico | `grainRetention` ↑, `lumaNrStrength` ↓ |
| Halos / crocante | `sharpenBurst` ↓, `sharpenOvershoot` ↓ |
| Diferença de cor entre lentes | matriz residual em `LensProfile` (carta de cores por lente) |
