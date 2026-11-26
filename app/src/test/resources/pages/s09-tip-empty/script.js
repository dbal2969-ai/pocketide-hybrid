function calculateTip() {
  const billInput = document.getElementById('billInput').value;
  const tipPercentInput = document.getElementById('tipPercentInput').value;

  const bill = parseFloat(billInput);
  const tipPercent = parseFloat(tipPercentInput);

  if (isNaN(bill) || isNaN(tipPercent) || bill < 0 || tipPercent < 0) {
    alert("Please enter valid positive numbers for the bill and tip percentage.");
    return;
  }

  const tipAmount = bill * (tipPercent / 100);
  const total = bill + tipAmount;

  document.getElementById('tipResult').innerHTML = `
    Tip: $${tipAmount.toFixed(2)}
    Total: ${total.toFixed(2)}
  `;
}

// Attach the calculation function to the button
document.getElementById('calculateBtn').addEventListener('click', calculateTip);