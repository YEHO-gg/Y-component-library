# CHRONOS 部署方案（Nginx）

纯静态站点，没有后端、没有构建、没有外部依赖。部署本质就是：**把 5 个文件放到一个目录，让 Nginx 指过去。**

## 1. 放文件

```bash
sudo mkdir -p /var/www/chronos
sudo rsync -av --exclude deploy --exclude README.md \
    apps/chronos/ /var/www/chronos/
sudo chown -R www-data:www-data /var/www/chronos
```

`/var/www/chronos` 里最终只应该有：`index.html`、`styles.css`、`app.js`、`preview.png`。

## 2. 配 Nginx

完整配置见 [`apps/chronos/deploy/nginx.conf`](../apps/chronos/deploy/nginx.conf)，关键点：

- `try_files $uri $uri/ /index.html` —— 单页无路由，找不到也回首页，别给 404
- **HTML 不缓存，资源 7～30 天**：文件名不带内容 hash，给 `immutable` 会把旧文件钉死在浏览器里
- **CSP 收得很紧**：页面不请求任何外部资源，`default-src 'self'`；只有两处需要 `'unsafe-inline'`（头部主题预置脚本、行内 SVG data URI）
- `gzip` 打开 css/js/html —— 原始体积约 100KB，压完 30KB 左右

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

不想占一个域名的话，挂在 `/chronos/` 下也可以：

```nginx
location /chronos/ {
    alias /var/www/chronos/;
    try_files $uri $uri/ /chronos/index.html;
}
```

⚠️ 页面里的资源都是相对路径（`./styles.css`），子路径下能正常工作；但**别用 `root` + 子目录**混着写，`alias` 和 `root` 的尾斜杠规则不一样，容易 404。

## 5. 验证清单

```bash
curl -I https://chronos.example.com/                    # 200 + CSP + no-cache
curl -sI https://chronos.example.com/styles.css | grep -i cache-control
nginx -t
```

浏览器里检查：断网后刷新应当**仍能正常显示**（零外部资源）；这就是这个站点最大的部署优势。

## 附：不用 Nginx 的替代

- **GitHub Pages**：把 `apps/chronos` 设为 Pages 目录即可，连服务器都不用
- **Cloudflare Pages / Netlify**：拖文件夹上传
- **本地**：`python -m http.server 8000`

三种方式都不需要改一行代码。
