# Publicar TripGuard no GitHub

O PC tem `gh` instalado, mas ainda falta fazer login.

## 1. Fazer login

No terminal:

```powershell
gh auth login
```

Escolhe:

- GitHub.com
- HTTPS
- Login with a web browser

## 2. Criar o repositório

Dentro de `E:\Claude\com.mystrodriver\tripguard`:

```powershell
gh repo create tripguard --public --source=. --remote=origin --push
```

## 3. Publicar o APK mais recente

```powershell
gh release create v0.1.1 release/TripGuard-latest.apk --title "TripGuard 0.1.1" --notes "Versao inicial com updates online e exportacao automatica."
```

## 4. Publicar o site simples

O ficheiro do site esta em:

- `site/index.html`

Podes usar GitHub Pages a partir da branch `main` e pasta `/site`.

## 5. URL do JSON

Depois de criares o repo, troca `OWNER` em:

- `tripguard-update.json`
- `release/tripguard-update.json`

Exemplo:

```json
"apkUrl": "https://github.com/teu-utilizador/tripguard/releases/latest/download/TripGuard-latest.apk"
```

## 6. URL esperada do site

Se ativares GitHub Pages para `/site`, fica algo como:

`https://teu-utilizador.github.io/tripguard/`
