const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.classList = {add() {}}; }
  append(...nodes) { this.children.push(...nodes); }
}
const document = {
  createElement: tag => new Element(tag),
  createTextNode: text => ({textContent: text})
};
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/chat.js'), 'utf8');
const context = vm.createContext({document, URL});
vm.runInContext(source.slice(source.indexOf('function inlineText('), source.indexOf('function append(')), context);
const descendants = node => [node, ...(node.children || []).flatMap(descendants)];
function render(text) {
  const root = new Element('div');
  context.renderAnswer(root, text);
  return descendants(root);
}
const url = 'http://192.168.2.52:18181/system/config';

test('renders the complete reported answer, including both bold escaped nested links', () => {
  const nodes = render(`根据系统知识库查询结果，系统设置页面的入口地址如下：
**[点击进入系统设置]\\([${url}](${url}))**
该页面主要用于进行以下集中配置：
- **系统数据对接**配置
- **展示范围**设置
- **自动添加设备**管理
- **算法参数**配置
如果您尚未登录系统，请先通过主入口进行登录：
**[点击进入系统主入口]\\([http://192.168.2.52:18181/a](http://192.168.2.52:18181/a))**`);
  const links = nodes.filter(n => n.tag === 'a');
  assert.equal(links.length, 2);
  assert.equal(links[0].href, url);
  assert.equal(links[0].textContent, '点击进入系统设置');
  assert.equal(links[1].href, 'http://192.168.2.52:18181/a');
  assert.equal(nodes.filter(n => n.tag === 'strong' && descendants(n).some(c => c.tag === 'a')).length, 2);
  for (const link of links) {
    assert.equal(link.target, '_blank');
    assert.equal(link.rel, 'noopener noreferrer');
  }
});
for (const text of [
  `[设置](${url})`, `**[设置](${url})**`,
  `[设置]\\(${url}\\)`, `[设置]([${url}](${url}))`,
  `[设置]\\([${url}](${url}))`, url
]) test(`clickable link: ${text}`, () => {
  const links = render(text).filter(n => n.tag === 'a');
  assert.equal(links.length, 1);
  assert.equal(links[0].href, url);
});

test('preserves code examples and never interprets HTML or unsafe schemes as links', () => {
  for (const text of [
    `\`[设置]\\([${url}](${url}))\``,
    `\`\`\`markdown\n[设置](${url})\n\`\`\``,
    '[设置](javascript:alert(1))', '[设置](data:text/html,hello)',
    '<img src=x onerror=alert(1)>'
  ]) {
    const nodes = render(text);
    assert.equal(nodes.filter(n => n.tag === 'a' || n.tag === 'img').length, 0);
    if (text.startsWith('`') && !text.startsWith('```'))
      assert.equal(nodes.find(n => n.tag === 'code').textContent, text.slice(1, -1));
  }
});

test('partial streaming links remain safe and become clickable when complete', () => {
  const text = `**[设置]\\([${url}](${url}))**`;
  for (let i = 0; i <= text.length; i++) {
    for (const node of render(text.slice(0, i)).filter(n => n.tag === 'a'))
      assert.ok(['http:', 'https:'].includes(new URL(node.href).protocol));
  }
  assert.equal(render(text).filter(n => n.tag === 'a').length, 1);
});
