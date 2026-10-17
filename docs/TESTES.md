# Relatório de testes

## Resumo honesto

| Categoria | Situação |
|---|---|
| Compilação do app (Kotlin, API 35) | ✅ sem erros |
| APK assinado (v3) | ✅ `tools/build-offline.sh` → `build/outputs/CameraPremium-debug.apk` (~0,95 MB) |
| Paleta monocromática da UI | ✅ verificada no build (`tools/check-palette.py`) |
| Somente API pública do Android | ✅ verificada no build contra API 35/36/main (`tools/check-public-api.py`) |
| Bytecode compatível com ART sem desugaring | ✅ verificado (0 `invokedynamic` no app; 63 métodos do stdlib usados, nenhum com `invokedynamic`) |
| Pipeline fotográfico (JVM) | ✅ 59/59 verificações objetivas (v0.3.3) |
| Fotos reais de amostra (JPEG públicos) | ✅ inspeção visual; um defeito encontrado e corrigido (ver abaixo) |
| Build Gradle/AGP | ⚠️ **não executado aqui** (repositórios Google bloqueados). Arquivos escritos para AGP 8.13 + Kotlin 2.2.21 |
| Instalação, abertura, câmeras, captura, salvamento | ❌ **não testados** — nenhum aparelho conectado neste ambiente |
| Qualidade em cenas reais do S26 Ultra / comparação com iPhone | ❌ **não testadas** |

Nenhuma câmera, lente ou fator de zoom foi validado no aparelho. A lista de câmeras e de
fatores efetivamente acessíveis só existe quando o app roda no S26 Ultra: use
**Ajustes → Diagnóstico da câmera → Exportar**.

## Suíte do pipeline (`tools/imaging-tests.sh`)

Cada teste traduz um requisito fotográfico em uma propriedade mensurável. Resultado atual:

```
cor: transferencias e Oklab          sRGB ida-e-volta erro 0.00000; Oklab 0.000002; cinza a=b=0
cor: CIEDE2000 (dados de Sharma)     2.0425 / 2.3669 (exatos)
wb: CCT pela calibracao do sensor    2850K->2859K  4000K->4005K  5500K->5499K  6500K->6499K  8000K->7985K
                                      tungstenio renderizado a 4877K (ambiencia parcial); luz do dia inalterada
tom: curva                           monotona; cinza 18% -> sRGB 128; +2,5 stops ainda a 0.878 (sem clip)
neutros                              rampas cinza: desvio entre canais 0 (todas as fontes)
carta de cores (fonte do sistema)    dE2000 medio 2.32; pior 3.64 (folhagem); maior desvio de matiz 3.6 graus
tons de pele (escala Monk 1-10)      matiz <= 4.6 graus; croma <= +5%; dL* <= 3.7; ordem e distancias preservadas
hdr: janela em contraluz             ceu: 0% branco estourado, nuvens com detalhe (faixa L* 12.9);
                                      interior L* 14.6 -> 37.9; sala continua bem mais escura que o ceu (profundidade)
nitidez                              overshoot na borda: 3 e 4 niveis (de 255); gradiente x1.26
ruido                                sigma L* 2.29 -> 1.26 (razao 0.55: reduz sem zerar); textura fina 78%
cabelo/pelo nao e ruido              energia de textura de fios mantida (>= 100%)
fusao multiquadro                    ruido rms 8.46 -> 3.74 com 6 quadros; objeto em movimento: 0 fantasmas
desempenho (host 4 threads)          pipeline 12 MP: ~2,5 s; fusao 4x12 MP: ~0,6 s
```

Saídas visuais em `docs/img/` (antes | depois):
`teste_hdr_contraluz.jpg`, `teste_carta_de_cores.jpg`, `teste_tons_de_pele.jpg`,
`teste_fusao_multiquadro.jpg`, `teste_nitidez_bordas.jpg`, `teste_textura_fios.jpg`.

### Defeitos encontrados e corrigidos durante a validação
1. **Fantasma de cor na fusão** — o teste de robustez olhava só a luma; um objeto vermelho com
   a mesma luminância do fundo era fundido. Corrigido incluindo croma no peso de robustez.
2. **Pele clara saturada** — uma regra "anti-pele acinzentada" adicionava croma a peles muito
   claras (naturalmente pouco cromáticas), homogeneizando tons. Removida: a pele nunca recebe
   croma extra.
3. **Céu desbotado** — o mapeamento de gamut levava azuis brilhantes ao branco. Novo
   mapeamento híbrido preserva o matiz e a saturação.
4. **Textura confundida com ruído (foto real)** — na pelagem de uma foto real o estimador
   media textura como ruído e a NR "pintava" a imagem. Corrigido com rejeição de patches
   anisotrópicos, cauda inferior da distribuição e NR mais leve para fontes já processadas
   pelo sistema. Adicionado teste de regressão.

### Pontos a observar (não são falhas, mas merecem ajuste com fotos reais)
- Em texturas limpas, a nitidez do caminho "quadro sRGB" aumenta a energia de textura em ~40%;
  sem halos, mas pode ser reduzida se parecer "crocante" nas comparações.
- Desempenho no aparelho é estimado: ART com 8 núcleos deve ficar na mesma ordem (1–3 s por
  foto de 12 MP, em segundo plano). Medir com "Mostrar tempos de processamento".

## Checklist no Galaxy S26 Ultra

Funcional
- [ ] Instala, abre, pede permissão, mostra preview fluido nas três proporções
- [ ] Diagnóstico: exportar e enviar o relatório (lentes, IDs, zoom, formatos, capacidades)
- [ ] Paradas de zoom exibidas correspondem às lentes; etiqueta da lente ativa muda ao trocar
- [ ] Pinça e arrasto na barra de zoom sem travar; troca de lente sem salto forte de cor/exposição
- [ ] Toque para focar, arrastar o sol (exposição), segurar para bloqueio AE/AF
- [ ] Flash auto/ligado/desligado; flash de tela na frontal
- [ ] Frontal: orientação correta em retrato e paisagem, espelhamento conforme a opção
- [ ] Captura em Foto/Noite/Pro/Retrato (se aparecer); timer; volume como disparador
- [ ] Arquivo na galeria (DCIM/CameraPremium), orientação correta, EXIF presente
- [ ] Comparação: mesmo enquadramento/orientação; trocar versão na galeria e voltar
- [ ] RAW (DNG) e alta resolução, se o aparelho oferecer
- [ ] 20 capturas seguidas sem travar nem erro de memória; trocar lentes durante o processamento

Qualidade (seguir o protocolo de comparação com iPhone)
- [ ] Luz natural, sol forte, interior, contraluz, noite, retrato, tons de pele variados,
      vegetação, cores intensas, detalhes finos, arquitetura

Logcat útil: `adb logcat -s CameraPremium` mostra a sessão escolhida, o plano do burst
(ISO/exposição/quadros) e os tempos de cada etapa.

## v0.2.0 — testes adicionados
```
nitidez: sem duplicar a do sistema     gradiente nao aumenta em fonte ja nitida pelo ISP; sem novos halos
nitidez: nao amplifica ruido           area lisa ruidosa: sigma L* 1.11 -> 0.54
pele: brilho especular contido         excesso local do brilho 17.5 -> 15.2 L* (volume mantido)
pele: suavizacao opcional              poros: gradiente 1.674 -> 1.592 (>90%); manchas p5..p95 4.12 -> 4.01
tons de pele (Monk 1-10)               pele clara nao e escurecida (bug do roll-off absoluto corrigido)
```
Interface (v0.2.0): compila; paleta verificada automaticamente. **Não foi possível executar a
interface** neste ambiente (sem aparelho/emulador): layout, toque no divisor, segmentos, sheets e
áreas seguras precisam ser conferidos no S26 Ultra.

## v0.3.1 — testes adicionados (a partir de 12 fotos reais do S26)
```
hibrido: cor falsa (arco-iris)        desvio de croma 0.0368 -> 0.0021 (limpo 0.0010); textura de luma 85%
                                      listras coloridas de 4 px: croma 0.1564 -> 0.1554
hibrido: veu cinza                    L* p1 30.4 -> 26.2; p99 76.0 -> 77.7; 0% estourado
                                      cena noturna: L* medio 49.5 -> 45.7
```
Fotos reais analisadas: 5 pares Original (HAL) x Natural Premium, ver `PIPELINE.md` §7. O efeito
das correções **no aparelho ainda não foi verificado**.

## v0.3.2
```
hibrido: pontos coloridos em sombra   desvio de croma 0.0044 -> 0.0024
tons de pele (fonte do sistema)       dL* max 3.7 -> 0.0 (exposicao do ISP confiada)
hibrido vs ISP (tecido)               erro L* 2.24 -> 1.52 (ISP 2.95)
```
Ver `PIPELINE.md` §8. Efeito no aparelho **não verificado**.

## v0.3.3
```
hibrido: metal serrilhado             desvio de croma 0.0641 -> 0.0070 (v0.3.2: 0.0140)
hibrido vs ISP (tecido)               erro L* 1.52 -> 1.20 (fusao crua 1.20; ISP 2.95)
```
Ver `PIPELINE.md` §9. Efeito no aparelho **não verificado**.
