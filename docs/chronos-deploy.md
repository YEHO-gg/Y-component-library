# CHRONOS 部署方案（Nginx）

纯静态站点，没有后端、没有构建、没有外部依赖。部署本质就是：**把静态文件放到一个目录，让 Nginx 指过去。**

真正需要上线的只有 3 个文件：`index.html`、`styles.css`、`app.js`。
`preview.png` 是仓库里的预览图，`README.md` 和 `deploy/` 是给维护者看的，都不用传到服务器。

## 1. 放文件

```bash
sudo mkdir -p /var/www/chronos
sudo rsync -av --exclude deploy --exclude README.md --exclude preview.png \
    apps/chronos/ /var/www/chronos/
sudo chown -R www-data:www-data /var/www/chronos
```

`/var/www/chronos` 里最终应该有：`index.html`、`styles.css`、`app.js`。
（想让预览图也能在线看，就单独放一份，别混进站点根目录。）

## 2. 配 Nginx

完整配置见 [`apps/chronos/deploy/nginx.conf`](../apps/chronos/deploy/nginx.conf)，关键点：

- `try_files $uri $uri/ /index.html` —— 单页无路由，找不到也回首页，别给 404
- **HTML 不缓存、资源 7～30 天**：文件名不带内容 hash，给 `immutable` 会把旧文件钉死在浏览器里
- **CSP 收得很紧**：`default-src 'self'`。只有 `script-src` 需要 `'unsafe-inline'`
  （`index.html` 头部那段主题预置脚本必须在首帧前执行，没法外链）；
  页面里没有 `style` 属性也没有 `<style>` 标签，所以 `style-src 'self'` 就够；
  图标是行内 `data:` SVG，由 `img-src 'self' data:` 覆盖。
- `gzip` 打开 css/js/html —— 原始体积约 100KB，压完约 **24KB**

⚠️ **改安全头前必读**：nginx 的 `add_header` 是"就近继承"——某个 `location` 里只要出现了
**任何一条** `add_header`，该 location 就不再继承 `server` 级的头。仓库里的配置刻意让所有
location 都不写 `add_header`（缓存用 `expires` 表达），这样 server 级的安全头才会真正下发。
否则就会出现"文档说 curl 能看到 CSP，实际响应里一个都没有"。

启用：

```bash
sudo ln -s /etc/nginx/sites-available/chronos /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

## 3. HTTPS

```bash
sudo certbot --nginx -d chronos.example.com
```

certbot 会自动改配置、加 80→443 跳转。仓库里的 `nginx.conf` 已经把 HTTPS 的 server 块写好并注释着，直接用也行。

## 4. 子路径部署（可选）

不想占一个域名的话，挂在 `/chronos/` 下。**用 `root` 而不是 `alias`**，因为 `alias` 配合
`try_files $uri` 时 `$uri` 没有经过 alias 映射，回退几乎一定 404：

```nginx
location = /chronos { return 301 /chronos/; }

location /chronos/ {
    root /var/www;                                  # → /var/www/chronos/...
    try_files $uri $uri/ /chronos/index.html;       # 回退也落在 /var/www/chronos/
}
```

页面里的资源都是相对路径（`./styles.css`），子路径下能正常工作。

## 5. 验证清单

```bash
curl -I https://chronos.example.com/ | grep -iE 'HTTP/|content-security-policy|cache-control'
curl -sI https://chronos.example.com/styles.css | grep -iE 'cache-control|expires'
sudo nginx -t
```

期望：首页 `200` + `Cache-Control: no-cache` + 一行非空的 `Content-Security-Policy`；
`styles.css` 是 `max-age=604800`。

浏览器里检查：**首次加载完成后**断网，页面仍能继续运行（零外部资源）。
但注意这里**没有 Service Worker / 离线缓存**：`index.html` 是 `no-cache`，
断网后**刷新**不在保证范围内（浏览器可能因为重新验证失败而报错）。

## 附：不用 Nginx 的替代

- **GitHub Pages**：原生 Pages 只能选仓库根目录或 `/docs`，直接指定 `apps/chronos` 是不行的。
  要么在 Actions 里把该目录作为产物发布，要么把文件复制到 `/docs`。
- **Cloudflare Pages / Netlify**：拖文件夹上传，或把 build 目录设成 `apps/chronos`
- **本地**：`cd apps/chronos && python -m http.server 8000`

除了 GitHub Pages 要绕一下，其余方式都不需要改一行代码。
