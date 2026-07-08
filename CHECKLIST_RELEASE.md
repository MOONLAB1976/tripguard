# TripGuard Release Checklist

## Objetivo

Garantir que cada nova versao funcional do TripGuard:
- compila sem erros
- nao parte o layout
- nao piora a leitura Uber/Bolt
- fica publicada no site para download fora de casa

## Antes de compilar

- confirmar que o trabalho novo nao desfez layout estavel
- confirmar que os estados de permissoes continuam visiveis na primeira pagina
- confirmar que o overlay/cartao fecha corretamente
- confirmar que os relatorios/diagnosticos continuam ativos
- confirmar que a logica nova nao trocou `Uber` com `Bolt`

## Build local

Na pasta:

```powershell
F:\Claude\com.mystrodriver\tripguard
```

Correr:

```powershell
.\gradlew.bat assembleDebug
```

Validar:
- `BUILD SUCCESSFUL`
- APK gerado em:

```text
F:\Claude\com.mystrodriver\tripguard\app\build\outputs\apk\debug\app-debug.apk
```

## Verificacao minima antes de publicar

- abrir a app e confirmar que arranca
- verificar que o menu inferior aparece bem
- verificar que o layout respeita telemoveis com 3 botoes Android
- verificar que a app nao ficou com ecra antigo ou componentes fora do sitio
- testar leitura de pelo menos 1 caso `Uber`
- testar leitura de pelo menos 1 caso `Bolt`
- testar cartoes simultaneos ou sequenciais quando possivel
- verificar que o cartao mostra valores coerentes:
  - tarifa total
  - EUR/km
  - EUR/h
  - recolha
  - viagem
  - codigos postais quando existirem

## Se falhar

- nao publicar no site uma build pior
- guardar diagnostico
- corrigir primeiro
- voltar a compilar

## Se passar

- copiar/publicar esta build como nova versao oficial
- atualizar a versao visivel para o utilizador
- publicar sempre no site para download fora de casa
- manter a versao anterior como referencia se for preciso rollback

## Publicacao

Confirmar que a versao publicada inclui:
- numero da versao
- data da atualizacao
- APK mais recente
- ligacao funcional para download no telemovel

## Depois da publicacao

- testar download pelo site no telemovel
- confirmar que instala
- confirmar que a versao instalada e a mais recente
- pedir teste real com:
  - Uber sozinha
  - Bolt sozinha
  - Uber + Bolt ao mesmo tempo

## Relatorios a rever apos release

- ultima leitura tecnica
- erros de parsing
- erros de overlay
- erros de instalacao
- capturas em que a Uber nao mostrou cartao
- capturas em que a Bolt deu valores errados

## Regra principal

Se houver nova build funcional, publicar sempre no site.
