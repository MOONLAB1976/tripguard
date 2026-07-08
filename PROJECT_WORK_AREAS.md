# TripGuard Work Areas

## Area 1: App

Core da leitura e da decisao das viagens.

- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\capture`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\domain`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\storage`

Aqui tratamos:

- leitura do ecra Uber e Bolt
- parser dos cartoes
- regras de aceitar ou recusar
- historico das ultimas 10 ofertas

## Area 2: Menus

Tudo o que o utilizador ve no ecran principal.

- `F:\Claude\com.mystrodriver\tripguard\app\src\main\res\layout\activity_main.xml`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\res\drawable`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\ui`

Aqui tratamos:

- organizacao visual dos cards
- botoes de permissoes
- futuros menus separados por paginas
- adaptacao para telemoveis e tablets

## Area 3: Performance

Parte para manter a app leve e rapida.

- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\ui\MainScreenStateFactory.kt`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\ui\MainScreenRenderer.kt`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\capture\TripAccessibilityService.kt`

Foco desta area:

- reduzir trabalho no `MainActivity`
- isolar a construcao do estado do ecran
- manter o overlay leve
- preparar a base para OCR, sync e IA sem misturar tudo

## Area 4: App e Confianca

Preparacao para estabilidade, distribuicao e suporte.

- `F:\Claude\com.mystrodriver\tripguard\app\src\main\java\pt\tripguard\app\trust`
- `F:\Claude\com.mystrodriver\tripguard\TRUST_DISTRIBUTION.md`
- `F:\Claude\com.mystrodriver\tripguard\app\src\main\AndroidManifest.xml`
- `F:\Claude\com.mystrodriver\tripguard\app\build.gradle.kts`

Aqui tratamos:

- versao instalada e readiness de update
- relatorios simples para enviar do telefone para o PC
- requisitos de Play Store, seguranca e permissoes
- limites para futura integracao de IA/API
- riscos de privacidade e automacao

## Como trabalhar por partes

Se quiseres dividir com outra IA ou com outro projeto:

1. `App`
   mexe em parser, filtros, acessibilidade e historico.
2. `Menus`
   mexe no layout, navegação e responsividade.
3. `Performance`
   mexe em velocidade de leitura, tempo de resposta, consumo de memoria e sincronizacao.
4. `App e Confianca`
   mexe em distribuicao, diagnosticos, versao, seguranca e requisitos de publicacao.
