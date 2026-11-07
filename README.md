# HitomicPayments

Projeto-fonte do plugin de integração da loja Hitomic MC com LuckPerms.

## Importante
Este ZIP contém o **código-fonte**, não um arquivo `.jar` pronto. O projeto ainda precisa ser compilado e testado no ambiente do servidor.

O webhook vem desativado por segurança (`webhook.enabled: false`) e escuta em `127.0.0.1` por padrão. Assim, a Pluga não consegue acessá-lo diretamente pela internet. Para produção será necessário configurar um endpoint HTTPS seguro/reverse proxy ou uma solução de túnel adequada. **Não exponha a porta HTTP diretamente na internet.**

## Compilação
Requer JDK 25 e Maven:
```bash
mvn clean package
```
O JAR gerado ficará em `target/HitomicPayments-1.0.0.jar`.

## Formato JSON esperado
```json
{
  "order_id": "12345",
  "nick": "NomeDoJogador",
  "product": "vipplus",
  "status": "paid"
}
```
Cabeçalho obrigatório: `X-Hitomic-Token`.

Confira a compatibilidade da dependência Paper com a versão exata do seu servidor antes de instalar. Não use em produção sem validar o build e testar a entrega de pedidos.
