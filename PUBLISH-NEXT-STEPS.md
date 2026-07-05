# Publicar atualizacoes do TripGuard

Nao e preciso "ligar" o Codex ao projeto em cada vez.

O importante e:

- o repositorio GitHub ja existe
- o site ja existe
- este projeto local ja tem o script de publicacao

Quando houver uma nova versao:

1. abre a pasta `_publish_tripguard`
2. corre `publish-github-update.ps1`
3. o script:
   - compila
   - atualiza o `tripguard-update.json`
   - prepara o APK
   - faz push para o GitHub
   - publica ou atualiza o Release

URLs atuais:

- repo: `https://github.com/MOONLAB1976/tripguard`
- site: `https://moonlab1976.github.io/tripguard/`
- update json: `https://raw.githubusercontent.com/MOONLAB1976/tripguard/master/tripguard-update.json`
