document.addEventListener('DOMContentLoaded', () => {
    const todoList = document.getElementById('todo-list');
    const taskInput = document.getElementById('taskInput');

    let tasks = JSON.parse(localStorage.getItem('todoTasks')) || [];

    const saveTasks = () => {
        localStorage.setItem('todoTasks', JSON.stringify(tasks));
    };

    const renderTasks = () => {
        todoList.innerHTML = '';
        tasks.forEach((task, index) => {
            const listItem = document.createElement('li');
            listItem.className = `todo-item ${task.completed ? 'done' : ''}`;
            listItem.innerHTML = `
                <span>${task.text}</span>
                <div>
                    <button class="action-btn" onclick="window.toggleTask(${index})">${task.completed ? 'Undo' : 'Complete'}</button>
                    <button class="action-btn" onclick="window.deleteTask(${index})">Delete</button>
                </div>
            `;
            todoList.appendChild(listItem);
        });
    };

    const addTask = () => {
        const taskText = taskInput.value.trim();
        if (taskText) {
            tasks.push({ text: taskText, completed: false });
            taskInput.value = '';
            saveTasks();
            renderTasks();
        }
    };

    window.toggleTask = (index) => {
        tasks[index].completed = !tasks[index].completed;
        saveTasks();
        renderTasks();
    };

    window.deleteTask = (index) => {
        tasks.splice(index, 1);
        saveTasks();
        renderTasks();
    };

    renderTasks();

    const addBtn = document.getElementById('addBtn');
    if (addBtn) {
        addBtn.addEventListener('click', addTask);
    }

    taskInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            addTask();
        }
    });
});
