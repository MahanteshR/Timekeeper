(() => {
  const examples = [
    { daily: 30, session: 10, hours: 3 },
    { daily: 45, session: 15, hours: 2 },
    { daily: 60, session: 20, hours: 1 }
  ];
  const buttons = [...document.querySelectorAll('[data-preset]')];
  buttons.forEach(button => button.addEventListener('click', () => {
    const example = examples[Number(button.dataset.preset)];
    if (!example) return;
    buttons.forEach(other => other.setAttribute('aria-pressed', String(other === button)));
    const setValue = (id, value, unit) => {
      const target = document.getElementById(id);
      target.replaceChildren(document.createTextNode(String(value)));
      const suffix = document.createElement('span'); suffix.textContent = unit; target.append(suffix);
    };
    setValue('daily-value', example.daily, 'min');
    setValue('session-value', example.session, 'min');
    setValue('cooldown-value', example.hours, example.hours === 1 ? 'hour' : 'hours');
    document.querySelectorAll('.session-number').forEach(item => { item.textContent = `${example.session} min`; });
    document.querySelectorAll('.break-label').forEach(item => { item.textContent = `${example.hours}h break`; });
    document.getElementById('demo-result').textContent = `Three ${example.session}-minute sessions, with a ${example.hours}-hour break between sessions.`;
  }));
})();
