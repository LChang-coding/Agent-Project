import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from '@/App.vue';
import router from '@/router';
import { useAuthStore } from '@/stores/auth';
import '@/styles/base.css';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

const authStore = useAuthStore();
authStore.bindHttpListener();

app.use(router);
app.mount('#app');
