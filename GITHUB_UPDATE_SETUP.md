# TripGuard GitHub Update

Usar um ficheiro JSON simples publicado no GitHub Pages, num gist raw, ou no repositório.

Exemplo de `tripguard-update.json`:

```json
{
  "versionCode": 3,
  "versionName": "0.1.2",
  "apkUrl": "https://github.com/OWNER/tripguard/releases/latest/download/TripGuard-latest.apk",
  "notes": "Correcao de exportacao e melhorias de estabilidade."
}
```

Fluxo recomendado:

1. Criar repositório `tripguard`
2. Publicar cada APK em `GitHub Releases`
3. Manter um `tripguard-update.json` atualizado
4. Na app, colar a URL pública desse JSON no campo de atualização online

Passos práticos neste projeto:

1. Compilar a app
2. Correr `prepare-github-release.ps1`
3. Abrir a pasta `release`
4. Publicar no GitHub Release:
   - `TripGuard-latest.apk`
5. Publicar o `tripguard-update.json` num URL público

Ficheiros preparados neste repositório:

- `tripguard-update.json`
- `prepare-github-release.ps1`
- `release\TripGuard-latest.apk` depois do script correr
- `release\tripguard-update.json` depois do script correr

Notas:

- `apkUrl` pode apontar para `releases/latest/download/TripGuard-latest.apk`
- a instalação continua a precisar de confirmação do utilizador no Android
- a verificação online apenas informa e descarrega o APK mais recente
